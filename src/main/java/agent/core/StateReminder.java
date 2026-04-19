package agent.core;

import agent.skills.SkillSessionState;
import agent.todo.TodoList;

/**
 * 会话动态状态摘要（System Reminder）。
 *
 * 【解决什么问题】
 * TodoList、已激活 skill 等"每轮都可能变化"的状态，如果拼进 system prompt，
 * 会让 system message 的前缀每轮都不同。由于 Prompt Caching 是字节级前缀匹配，
 * system 一变 → 整个请求从 system message 往后的 KV-Cache 全部失效 → 成本飙升、首 token 延迟变大。
 *
 * 【采用方案】
 * 把动态状态作为 {@code <system-reminder>} 块追加到本轮最后一条 tool_result 的 content 末尾。
 * tool_result 本来就是每轮新增的消息，在它末尾附加内容不会破坏前面已缓存的 prefix。
 *
 * 这是 Claude Code 在实际 session 里能观察到的设计模式，背后的原理是 cache-aware context engineering。
 */
public class StateReminder {

    private final TodoList todoList;
    private final SkillSessionState sessionState;

    public StateReminder(TodoList todoList, SkillSessionState sessionState) {
        this.todoList = todoList;
        this.sessionState = sessionState;
    }

    /**
     * 生成要附加到 tool_result 末尾的 {@code <system-reminder>} 块。
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
