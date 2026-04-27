package agent.core;

import agent.skills.SkillSessionState;
import agent.todo.TodoList;

/**
 * 会话动态状态摘要。
 *
 * 【解决什么问题】
 * TodoList、已激活 skill 等"每轮都可能变化"的状态，不能拼进 system prompt
 * （会破坏 Prompt Cache 的前缀匹配），也不能烘焙进 tool_result（会污染历史、浪费 token）。
 *
 * 【采用方案：Ephemeral Injection（临时注入）】
 * 本类生成的状态摘要，由 ReActLoop 在构建 API 请求时作为临时消息追加到 messages 末尾。
 * 关键点：这条消息**不写入持久化的对话历史**，下次请求时会被最新版本替换。
 *
 * 好处：
 * - 历史消息干净，不累积过期状态 → 节省 token
 * - 只在 messages 末尾追加 → 不破坏前缀缓存（缓存是前缀匹配，末尾变化不影响前面）
 * - 每次请求只携带当前最新状态 → 信息密度最高
 */
public class StateReminder {

    private final TodoList todoList;
    private final SkillSessionState sessionState;

    public StateReminder(TodoList todoList, SkillSessionState sessionState) {
        this.todoList = todoList;
        this.sessionState = sessionState;
    }

    /**
     * 生成要临时注入到本轮请求末尾的 {@code <system-reminder>} 块。
     * 所有状态都为空时返回空字符串——不追加任何内容，连包裹标签都不加。
     */
    public String buildReminder() {
        StringBuilder body = new StringBuilder();

        // 已激活的 skills（SkillSessionState，每次 activate_skill 后变化）
        if (sessionState.activeCount() > 0) {
            body.append("已激活的 skills:\n");
            for (String name : sessionState.getActiveSkills()) {
                body.append("- ").append(name).append("\n");
            }
        }

        // 当前 todo 清单（TodoList，每次 todo_write 后变化）
        if (!todoList.isEmpty()) {
            if (body.length() > 0) {
                body.append("\n");
            }
            body.append(todoList.toPromptSection());
        }

        if (body.length() == 0) {
            return "";
        }

        return "\n\n<system-reminder>\n" + body.toString().trim() + "\n</system-reminder>";
    }
}
