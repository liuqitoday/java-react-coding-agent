package agent;

import agent.skills.SkillPromptBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * ReAct（Reasoning + Acting）循环的核心编排器。
 *
 * 完整流程：
 * 1. 每轮调用前，通过 SkillPromptBuilder 动态更新 system prompt（反映最新的 skill 状态）
 * 2. 将 messages（对话历史）和 tools（工具定义）发送给 LLM
 * 3. 解析响应：如果包含 tool_calls，说明 LLM 决定调用工具
 *    - 提取 content 字段作为 Thought（思考过程）
 *    - 逐个执行 tool_call，将结果以 role=tool 消息追加到历史
 *    - 再次调用 LLM，让它看到工具执行结果
 * 4. 如果响应不包含 tool_calls，说明 LLM 已得出最终答案，循环结束
 * 5. 设有 maxIterations 上限，防止无限循环
 */
public class ReActLoop {

    private final LLMClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ConsoleRenderer renderer;
    private final int maxIterations;
    private final SkillPromptBuilder promptBuilder;

    public ReActLoop(LLMClient llmClient, ToolRegistry toolRegistry,
                     ConsoleRenderer renderer, int maxIterations,
                     SkillPromptBuilder promptBuilder) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.renderer = renderer;
        this.maxIterations = maxIterations;
        this.promptBuilder = promptBuilder;
    }

    /**
     * 针对当前用户输入执行 ReAct 循环。
     * 调用前需确保用户消息已加入 history。
     */
    public void run(ConversationHistory history) {
        JsonArray tools = toolRegistry.toJsonArray();

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            renderer.renderSeparator();

            // 每轮调用前动态更新 system prompt（反映最新的 skill 激活状态）
            if (promptBuilder != null) {
                history.updateSystemPrompt(promptBuilder.build());
            }

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
                // 显示思考过程
                renderer.renderThought(content);

                // 将包含 tool_calls 的完整 assistant 消息加入历史
                // （API 要求 tool result 消息之前必须有对应的 assistant 消息）
                history.addAssistantMessage(message);

                // 逐个处理工具调用
                JsonArray toolCalls = message.getAsJsonArray("tool_calls");
                for (JsonElement tc : toolCalls) {
                    JsonObject toolCall = tc.getAsJsonObject();
                    String toolCallId = toolCall.get("id").getAsString();
                    JsonObject function = toolCall.getAsJsonObject("function");
                    String toolName = function.get("name").getAsString();
                    String argsString = function.get("arguments").getAsString();

                    // 显示动作
                    renderer.renderAction(toolName, argsString);

                    // 解析工具参数并执行
                    JsonObject args;
                    try {
                        args = JsonParser.parseString(argsString).getAsJsonObject();
                    } catch (Exception e) {
                        String errorMsg = "工具参数解析失败：" + e.getMessage();
                        renderer.renderObservation(errorMsg);
                        history.addToolResult(toolCallId, errorMsg);
                        continue;
                    }

                    ToolResult result = toolRegistry.execute(toolName, args);

                    // 显示观察结果
                    renderer.renderObservation(result.output());

                    // 将工具执行结果以 role=tool 消息加入历史，供下一轮 LLM 参考
                    history.addToolResult(toolCallId, result.output());
                }

                // 继续循环——LLM 将在下一轮看到工具执行结果
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
}
