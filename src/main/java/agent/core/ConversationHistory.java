package agent.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
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

    private static final String SUMMARY_MARKER = "[[COMPACTED_HISTORY]]";
    private static final String SUMMARY_INSTRUCTION =
            SUMMARY_MARKER + "\n以下是更早对话的摘要，请将其视为已知上下文。";

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

    /**
     * 粗略估算本轮请求 token 数。
     *
     * 这是学习项目里的近似值：按字符数估算，目标是发现"历史已经太长了"，
     * 不追求与真实 tokenizer 完全一致。
     */
    public int estimateTokens(String ephemeralContext) {
        int total = estimateTextTokens(systemPrompt) + 8;
        for (JsonObject msg : messages) {
            total += estimateMessageTokens(msg);
        }
        if (ephemeralContext != null && !ephemeralContext.isBlank()) {
            total += estimateTextTokens(ephemeralContext) + 8;
        }
        return total;
    }

    /** 返回当前可被压缩的较早 turn；最近 keepLastTurns 个 turn 会被保留。 */
    public List<List<JsonObject>> getTurnsToCompact(int keepLastTurns) {
        List<List<JsonObject>> turns = splitIntoTurns(conversationMessages());
        if (turns.size() <= keepLastTurns) {
            return Collections.emptyList();
        }

        List<List<JsonObject>> result = new ArrayList<>();
        for (int i = 0; i < turns.size() - keepLastTurns; i++) {
            result.add(new ArrayList<>(turns.get(i)));
        }
        return result;
    }

    /** 返回当前已存在的滚动摘要；若还未压缩过则返回空字符串。 */
    public String existingSummary() {
        if (!hasSyntheticSummary()) {
            return "";
        }

        JsonObject assistantSummary = messages.get(1);
        if (!assistantSummary.has("content") || assistantSummary.get("content").isJsonNull()) {
            return "";
        }
        return assistantSummary.get("content").getAsString();
    }

    /**
     * 用新的摘要替换较早历史，只保留最近 keepLastTurns 个原始 turn。
     *
     * @return 被压缩掉的 turn 数量
     */
    public int replaceOlderTurnsWithSummary(String summary, int keepLastTurns) {
        if (summary == null || summary.isBlank()) {
            return 0;
        }

        List<JsonObject> conversationMessages = conversationMessages();
        List<List<JsonObject>> turns = splitIntoTurns(conversationMessages);
        if (turns.size() <= keepLastTurns) {
            return 0;
        }

        int compactedTurnCount = turns.size() - keepLastTurns;
        List<JsonObject> newMessages = new ArrayList<>();
        newMessages.add(buildSummaryUserMessage());
        newMessages.add(buildSummaryAssistantMessage(summary));

        for (int i = compactedTurnCount; i < turns.size(); i++) {
            for (JsonObject msg : turns.get(i)) {
                newMessages.add(msg.deepCopy());
            }
        }

        messages.clear();
        messages.addAll(newMessages);
        return compactedTurnCount;
    }

    private boolean hasSyntheticSummary() {
        if (messages.size() < 2) {
            return false;
        }

        JsonObject first = messages.get(0);
        JsonObject second = messages.get(1);
        if (!"user".equals(getRole(first)) || !"assistant".equals(getRole(second))) {
            return false;
        }
        if (!first.has("content") || first.get("content").isJsonNull()) {
            return false;
        }
        return first.get("content").getAsString().startsWith(SUMMARY_MARKER);
    }

    private List<JsonObject> conversationMessages() {
        int start = hasSyntheticSummary() ? 2 : 0;
        List<JsonObject> result = new ArrayList<>();
        for (int i = start; i < messages.size(); i++) {
            result.add(messages.get(i));
        }
        return result;
    }

    private List<List<JsonObject>> splitIntoTurns(List<JsonObject> sourceMessages) {
        List<List<JsonObject>> turns = new ArrayList<>();
        List<JsonObject> currentTurn = null;

        for (JsonObject msg : sourceMessages) {
            String role = getRole(msg);
            if ("user".equals(role)) {
                if (currentTurn != null && !currentTurn.isEmpty()) {
                    turns.add(currentTurn);
                }
                currentTurn = new ArrayList<>();
            }

            if (currentTurn == null) {
                return Collections.emptyList();
            }
            currentTurn.add(msg);
        }

        if (currentTurn != null && !currentTurn.isEmpty()) {
            turns.add(currentTurn);
        }
        return turns;
    }

    private JsonObject buildSummaryUserMessage() {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", SUMMARY_INSTRUCTION);
        return msg;
    }

    private JsonObject buildSummaryAssistantMessage(String summary) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "assistant");
        msg.addProperty("content", summary);
        return msg;
    }

    private String getRole(JsonObject msg) {
        if (!msg.has("role") || msg.get("role").isJsonNull()) {
            return "";
        }
        return msg.get("role").getAsString();
    }

    private int estimateMessageTokens(JsonObject msg) {
        return estimateTextTokens(msg.toString()) + 8;
    }

    private int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int asciiChars = 0;
        int nonAsciiChars = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) <= 127) {
                asciiChars++;
            } else {
                nonAsciiChars++;
            }
        }

        int asciiTokens = (asciiChars + 3) / 4;
        int nonAsciiTokens = (nonAsciiChars + 1) / 2;
        return Math.max(1, asciiTokens + nonAsciiTokens);
    }
}
