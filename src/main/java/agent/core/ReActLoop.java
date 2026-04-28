package agent.core;

import agent.llm.LLMClient;
import agent.permission.PermissionGate;
import agent.render.ConsoleRenderer;
import agent.todo.TodoList;
import agent.tools.ToolRegistry;
import agent.tools.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * ReAct（Reasoning + Acting）循环的核心编排器。
 *
 * 完整流程：
 * 1. System prompt 在 ConversationHistory 构造时一次性注入，Session 内保持不变
 * 2. 每轮把 messages（对话历史）和 tools（工具定义）发送给 LLM
 * 3. 解析响应：如果包含 tool_calls，说明 LLM 决定调用工具
 *    - 提取 content 字段作为 Thought（思考过程）
 *    - 逐个执行 tool_call，将 tool_result 干净地加入历史（不附加任何额外内容）
 *    - 再次调用 LLM 时，通过 ephemeral injection 临时注入当前动态状态
 * 4. 如果响应不包含 tool_calls，说明 LLM 已得出最终答案，循环结束
 * 5. 设有 maxIterations 上限，防止无限循环
 *
 * 【动态状态注入策略】
 * TodoList、已激活 Skill 等动态状态通过 StateReminder 生成，
 * 在构建 API 请求时作为临时消息追加到 messages 末尾，**不写入持久化的对话历史**。
 * 这样做的好处：
 * - 历史消息干净，不累积过期状态 → 节省 token
 * - 临时消息在 messages 末尾 → 不破坏前缀缓存
 */
public class ReActLoop {

    private static final int KEEP_LAST_TURNS = 3;

    private final LLMClient llmClient;
    private final Compactor compactor;
    private final ToolRegistry toolRegistry;
    private final ConsoleRenderer renderer;
    private final int maxIterations;
    private final StateReminder stateReminder;
    private final PermissionGate gate;
    private final TodoList todoList;
    private final boolean autoCompactEnabled;
    private final int autoCompactThreshold;

    public ReActLoop(LLMClient llmClient, Compactor compactor, ToolRegistry toolRegistry,
                     ConsoleRenderer renderer, int maxIterations,
                     StateReminder stateReminder, PermissionGate gate,
                     TodoList todoList, boolean autoCompactEnabled,
                     int autoCompactThreshold) {
        this.llmClient = llmClient;
        this.compactor = compactor;
        this.toolRegistry = toolRegistry;
        this.renderer = renderer;
        this.maxIterations = maxIterations;
        this.stateReminder = stateReminder;
        this.gate = gate;
        this.todoList = todoList;
        this.autoCompactEnabled = autoCompactEnabled;
        this.autoCompactThreshold = autoCompactThreshold;
    }

    /**
     * 针对当前用户输入执行 ReAct 循环。
     * 调用前需确保用户消息已加入 history。
     */
    public void run(ConversationHistory history) {
        JsonArray tools = toolRegistry.toJsonArray();

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            renderer.renderSeparator();

            String reminder = stateReminder.buildReminder();
            compactHistoryIfNeeded(history, reminder);

            // 第一步：调用 LLM
            // 动态状态（TodoList 等）通过 ephemeral injection 临时注入到 messages 末尾，
            // 不写入持久历史——既避免污染历史消息，也不影响前缀缓存。
            JsonObject response;
            try {
                response = llmClient.chatCompletion(history.toJsonArray(reminder), tools);
            } catch (Exception e) {
                renderer.renderError("LLM 调用失败：" + e.getMessage());
                return;
            }

            // 第二步：从响应中提取 assistant 消息
            JsonObject choice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");

            // 提取 content 字段——在有 tool_calls 时作为 Thought，否则作为最终回答
            String content = null;
            if (message.has("content") && !message.get("content").isJsonNull()) {
                content = message.get("content").getAsString();
            }

            // 第三步：判断是否有工具调用
            if (message.has("tool_calls") && !message.getAsJsonArray("tool_calls").isEmpty()) {
                renderer.renderThought(content);

                // 将包含 tool_calls 的完整 assistant 消息加入历史
                // （API 要求 tool result 消息之前必须有对应的 assistant 消息）
                history.addAssistantMessage(message);

                // 逐个处理工具调用
                JsonArray toolCalls = message.getAsJsonArray("tool_calls");
                int total = toolCalls.size();
                for (int i = 0; i < total; i++) {
                    JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
                    String toolCallId = toolCall.get("id").getAsString();
                    JsonObject function = toolCall.getAsJsonObject("function");
                    String toolName = function.get("name").getAsString();
                    String argsString = function.get("arguments").getAsString();

                    renderer.renderAction(toolName, argsString);


                    // 解析工具参数
                    JsonObject args;
                    try {
                        args = JsonParser.parseString(argsString).getAsJsonObject();
                    } catch (Exception e) {
                        ToolResult errorResult = ToolResult.error("工具参数解析失败：" + e.getMessage());
                        renderer.renderObservation(toolName, errorResult);
                        history.addToolResult(toolCallId, errorResult.output());
                        continue;
                    }

                    // 权限前置检查：命中 deny 或 ASK 被用户拒绝时，直接以错误结果替代工具执行
                    String denialReason = gate.check(toolName, args);
                    ToolResult result = (denialReason != null)
                            ? ToolResult.error(denialReason)
                            : toolRegistry.execute(toolName, args);
                    renderer.renderObservation(toolName, result);
                    if (result.success() && "todo_write".equals(toolName)) {
                        renderer.renderTodo(todoList);
                    }

                    // tool_result 干净入历史，不附加任何额外内容
                    history.addToolResult(toolCallId, result.output());
                }

                // 继续循环——下一轮调用 LLM 时会通过 ephemeral injection 注入最新状态
                continue;
            }

            // 没有工具调用——这是最终回答
            if (content != null) {
                history.addAssistantMessage(message);
                renderer.renderFinalAnswer(content);
                clearCompletedTodos();
            }
            return;
        }

        // 超过最大迭代次数，强制终止
        renderer.renderIterationWarning(maxIterations);
    }


    private void clearCompletedTodos() {
        if (todoList.allCompleted()) {
            todoList.clear();
        }
    }

    private void compactHistoryIfNeeded(ConversationHistory history, String reminder) {
        if (!autoCompactEnabled) {
            return;
        }

        int estimatedTokens = history.estimateTokens(reminder);
        if (estimatedTokens <= autoCompactThreshold) {
            return;
        }

        var turnsToCompact = history.getTurnsToCompact(KEEP_LAST_TURNS);
        if (turnsToCompact.isEmpty()) {
            return;
        }

        try {
            String summary = compactor.compact(history.existingSummary(), turnsToCompact);
            int compactedTurns = history.replaceOlderTurnsWithSummary(summary, KEEP_LAST_TURNS);
            if (compactedTurns > 0) {
                System.out.println("上下文过长（约 " + estimatedTokens + " tokens），已将较早的 "
                        + compactedTurns + " 轮对话压缩为摘要。");
            }
        } catch (Exception e) {
            System.out.println("上下文压缩失败，继续使用原始历史：" + e.getMessage());
        }
    }
}
