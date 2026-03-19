package agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史管理器。
 *
 * 维护发送给 LLM 的 messages 数组，包含四种角色的消息：
 * - system:    系统提示词，定义 Agent 的角色（仅在初始化时添加一次）
 * - user:      用户的输入
 * - assistant: LLM 的回复（可能包含 tool_calls 字段）
 * - tool:      工具执行结果（必须携带 tool_call_id 与 assistant 的 tool_call 对应）
 */
public class ConversationHistory {

    private final List<JsonObject> messages = new ArrayList<>();

    public ConversationHistory(String systemPrompt) {
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messages.add(systemMsg);
    }

    /** 添加用户消息。 */
    public void addUserMessage(String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", content);
        messages.add(msg);
    }

    /** 添加 assistant 消息（原始 JSON，可能包含 tool_calls 字段）。 */
    public void addAssistantMessage(JsonObject assistantMessage) {
        messages.add(assistantMessage);
    }

    /** 添加工具执行结果消息。tool_call_id 用于关联对应的 tool_call。 */
    public void addToolResult(String toolCallId, String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "tool");
        msg.addProperty("tool_call_id", toolCallId);
        msg.addProperty("content", content);
        messages.add(msg);
    }

    /** 将所有消息转为 JsonArray，用于 API 请求的 messages 字段。 */
    public JsonArray toJsonArray() {
        JsonArray array = new JsonArray();
        for (JsonObject msg : messages) {
            array.add(msg);
        }
        return array;
    }
}
