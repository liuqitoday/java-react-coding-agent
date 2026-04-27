package agent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史管理器。
 *
 * 持久化保存会话历史中的 user / assistant / tool 消息。
 *
 * system prompt 作为 Session 级不变量，不存进 messages 列表，而是在序列化请求时
 * 始终拼接在最前面。当前回合的动态状态（TodoList、已激活 Skill）如需注入，
 * 也只会在构建请求时以临时 developer message 追加到末尾，不写回持久历史。
 */
public class ConversationHistory {

    /** 对话消息（不含 system message） */
    private final List<JsonObject> messages = new ArrayList<>();

    /** Session 级静态 system prompt。 */
    private final String systemPrompt;

    public ConversationHistory(String systemPrompt) {
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
     * system message 始终在最前面，内容为 Session 固定的 systemPrompt。
     */
    public JsonArray toJsonArray() {
        return toJsonArray(null);
    }

    /**
     * 构建包含临时上下文的 messages 数组。
     *
     * ephemeralContext 作为最后一条消息追加到数组末尾，但**不会写入 messages 列表**。
     * 下次调用时，它会被最新版本替换——历史消息始终干净，不会累积过期状态。
     *
     * 放在末尾不影响 Prompt Cache：缓存是前缀匹配，末尾的新内容本来就是 cache miss，
     * 前面的 system + 历史消息仍然完整命中缓存。
     */
    public JsonArray toJsonArray(String ephemeralContext) {
        JsonArray array = new JsonArray();

        // system message 始终在最前面
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        array.add(systemMsg);

        for (JsonObject msg : messages) {
            array.add(msg);
        }

        // 临时注入动态状态（TodoList、已激活 Skill 等），不写入持久历史。
        // 使用 developer 角色：语义上这是 harness 注入的上下文，不是用户输入。
        // 注意：developer 角色是 OpenAI 新模型支持的，部分兼容 API 可能不支持，
        //       如遇到问题可改回 "user" 并用 XML 标签区分。
        if (ephemeralContext != null && !ephemeralContext.isEmpty()) {
            JsonObject ctx = new JsonObject();
            ctx.addProperty("role", "developer");
            ctx.addProperty("content", ephemeralContext);
            array.add(ctx);
        }

        return array;
    }
}
