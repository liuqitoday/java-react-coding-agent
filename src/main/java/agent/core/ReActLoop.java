package agent.core;

import agent.llm.LLMClient;
import agent.permission.PermissionGate;
import agent.render.ConsoleRenderer;
import agent.tools.ToolRegistry;
import agent.tools.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * ReAct（Reasoning + Acting）循环的核心编排器。
 *
 * 完整流程：
 * 1. System prompt 在 ConversationHistory 构造时一次性注入，Session 内保持不变——
 *    动态状态通过 StateReminder 走 tool_result 尾部注入，最大化 Prompt Cache 命中率
 * 2. 每轮把 messages（对话历史）和 tools（工具定义）发送给 LLM
 * 3. 解析响应：如果包含 tool_calls，说明 LLM 决定调用工具
 *    - 提取 content 字段作为 Thought（思考过程）
 *    - 逐个执行 tool_call
 *    - 本轮最后一个 tool_result 末尾追加 {@code <system-reminder>} 注入当前动态状态
 *    - 将所有 tool_result 消息追加到历史，再次调用 LLM
 * 4. 如果响应不包含 tool_calls，说明 LLM 已得出最终答案，循环结束
 * 5. 设有 maxIterations 上限，防止无限循环
 */
public class ReActLoop {

    private final LLMClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ConsoleRenderer renderer;
    private final int maxIterations;
    private final StateReminder stateReminder;
    private final PermissionGate gate;

    public ReActLoop(LLMClient llmClient, ToolRegistry toolRegistry,
                     ConsoleRenderer renderer, int maxIterations,
                     StateReminder stateReminder, PermissionGate gate) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.renderer = renderer;
        this.maxIterations = maxIterations;
        this.stateReminder = stateReminder;
        this.gate = gate;
    }

    /**
     * 针对当前用户输入执行 ReAct 循环。
     * 调用前需确保用户消息已加入 history。
     */
    public void run(ConversationHistory history) {
        JsonArray tools = toolRegistry.toJsonArray();

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            renderer.renderSeparator();

            // 注意：这里不再调用 history.updateSystemPrompt()——system prompt 是启动时
            // 就定格的不变量。动态状态通过 tool_result 尾部的 <system-reminder> 注入。

            // 第一步：调用 LLM
            JsonObject response;
            try {
                response = llmClient.chatCompletion(history.toJsonArray(), tools);
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

                    boolean isLast = (i == total - 1);

                    // 解析工具参数
                    JsonObject args;
                    try {
                        args = JsonParser.parseString(argsString).getAsJsonObject();
                    } catch (Exception e) {
                        String errorMsg = "工具参数解析失败：" + e.getMessage();
                        renderer.renderObservation(errorMsg);
                        history.addToolResult(toolCallId, maybeAppendReminder(errorMsg, isLast));
                        continue;
                    }

                    // 权限前置检查：命中 deny 或 ASK 被用户拒绝时，直接以错误结果替代工具执行
                    String denialReason = gate.check(toolName, args);
                    ToolResult result = (denialReason != null)
                            ? ToolResult.error(denialReason)
                            : toolRegistry.execute(toolName, args);
                    renderer.renderObservation(result.output());

                    // 本轮最后一个 tool_result 末尾附加 <system-reminder>——注入动态状态
                    history.addToolResult(toolCallId, maybeAppendReminder(result.output(), isLast));
                }

                // 继续循环——LLM 将在下一轮看到工具执行结果 + 附带的 reminder
                continue;
            }

            // 没有工具调用——这是最终回答
            if (content != null) {
                renderer.renderFinalAnswer(content);
            }
            return;
        }

        // 超过最大迭代次数，强制终止
        renderer.renderIterationWarning(maxIterations);
    }

    /**
     * 本轮最后一条 tool_result 才追加动态状态 reminder；其他原样返回。
     * 只注入到最后一条的理由：避免同一轮多个 tool_call 时重复冗余的 reminder 片段，
     * LLM 顺序读到最后一条就能拿到当前最新状态，信息密度最高。
     */
    private String maybeAppendReminder(String content, boolean isLast) {
        return isLast ? content + stateReminder.buildReminder() : content;
    }
}
