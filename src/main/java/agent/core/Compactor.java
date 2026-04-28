package agent.core;

import agent.llm.LLMClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 将较早对话历史压缩成一段滚动摘要。
 *
 * 学习项目里不引入独立摘要模型，也不做复杂压缩树；
 * 直接复用主模型，把旧 turn 总结成后续继续工作所需的高层事实。
 */
public class Compactor {

    private static final String SYSTEM_PROMPT = """
            你是一个对话历史压缩器。

            你的任务是把编码助手较早的对话历史压缩成一段短摘要，供后续轮次继续使用。

            请只保留：
            1. 用户当前总体目标和子目标
            2. 已确认的重要事实
            3. 已读过的关键文件、命令结论、工具执行结果结论
            4. 已做出的关键决策、已完成步骤、未解决问题

            请不要：
            - 复制大段文件内容
            - 复制大段命令输出
            - 编造历史中不存在的事实

            输出格式固定为四段：
            当前目标:
            已确认事实:
            关键变更/决定:
            未解决事项:
            """;

    private final LLMClient llmClient;

    public Compactor(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    public String compact(String existingSummary, List<List<JsonObject>> turnsToCompact) throws Exception {
        JsonArray messages = new JsonArray();

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", SYSTEM_PROMPT);
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", buildPrompt(existingSummary, turnsToCompact));
        messages.add(user);

        JsonObject response = llmClient.chatCompletion(messages, null);
        JsonObject choice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
        JsonObject message = choice.getAsJsonObject("message");

        if (!message.has("content") || message.get("content").isJsonNull()) {
            throw new RuntimeException("上下文压缩失败：摘要响应为空");
        }

        String summary = message.get("content").getAsString().trim();
        if (summary.isEmpty()) {
            throw new RuntimeException("上下文压缩失败：摘要响应为空");
        }
        return summary;
    }

    private String buildPrompt(String existingSummary, List<List<JsonObject>> turnsToCompact) {
        StringBuilder sb = new StringBuilder();

        if (existingSummary != null && !existingSummary.isBlank()) {
            sb.append("这是之前已经压缩过的一版历史摘要：\n");
            sb.append(existingSummary.trim()).append("\n\n");
        }

        sb.append("下面是需要继续压缩的较早对话历史。\n");
        sb.append("请把已有摘要和这些历史融合成一版新的滚动摘要。\n\n");

        for (int i = 0; i < turnsToCompact.size(); i++) {
            sb.append("## Turn ").append(i + 1).append("\n");
            for (JsonObject msg : turnsToCompact.get(i)) {
                appendMessage(sb, msg);
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }

    private void appendMessage(StringBuilder sb, JsonObject msg) {
        String role = msg.has("role") && !msg.get("role").isJsonNull()
                ? msg.get("role").getAsString()
                : "unknown";

        sb.append("[").append(role).append("]\n");
        if (msg.has("content") && !msg.get("content").isJsonNull()) {
            sb.append(msg.get("content").getAsString()).append("\n");
        }

        if (msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()) {
            JsonArray toolCalls = msg.getAsJsonArray("tool_calls");
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
                JsonObject function = toolCall.getAsJsonObject("function");
                String name = function.get("name").getAsString();
                String args = function.get("arguments").getAsString();
                sb.append("tool_call: ").append(name).append("(").append(args).append(")\n");
            }
        }
    }
}
