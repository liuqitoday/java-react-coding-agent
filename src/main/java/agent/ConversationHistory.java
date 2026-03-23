package agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史管理器。
 *
 * 维护发送给 LLM 的 messages 数组，包含四种角色的消息：
 * - system:    系统提示词，由 SkillPromptBuilder 动态生成（每次请求前更新）
 * - user:      用户的输入
 * - assistant: LLM 的回复（可能包含 tool_calls 字段）
 * - tool:      工具执行结果（必须携带 tool_call_id 与 assistant 的 tool_call 对应）
 *
 * system message 与对话消息分开管理：对话消息只包含 user/assistant/tool，
 * system message 在 toJsonArray() 时动态拼接在最前面。
 * 这样 system prompt 可以随 skill 状态变化而动态更新。
 */
public class ConversationHistory {

    /** 对话消息（不含 system message） */
    private final List<JsonObject> messages = new ArrayList<>();

    /** 当前 system prompt，可动态更新 */
    private String systemPrompt;

    public ConversationHistory(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    /** 更新 system prompt（每次 LLM 调用前由 SkillPromptBuilder 调用）。 */
    public void updateSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
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

    /**
     * 将 system message + 对话消息转为 JsonArray，用于 API 请求的 messages 字段。
     * system message 始终在最前面，内容为最新的 systemPrompt。
     */
    public JsonArray toJsonArray() {
        JsonArray array = new JsonArray();

        // system message 始终在最前面
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        array.add(systemMsg);

        for (JsonObject msg : messages) {
            array.add(msg);
        }
        return array;
    }
}
