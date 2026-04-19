package agent.todo;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话级 Todo 列表。
 *
 * 本质是"LLM 的外部工作记忆"：
 * - LLM 通过 todo_write 工具把自己拆分出的任务写进来
 * - SystemPromptBuilder 在每轮调用前读取这份列表，注入 system prompt
 * - LLM 持续看到自己的计划 → 规划行为被强化
 *
 * Session 内可变，整个 Agent 生命周期共享一份实例（与 SkillSessionState 模式对齐）。
 */
public class TodoList {

    private final List<TodoItem> items = new ArrayList<>();

    /** 全量替换当前列表（todo_write 的语义）。 */
    public void replace(List<TodoItem> newItems) {
        items.clear();
        items.addAll(newItems);
    }

    /** 返回只读快照。 */
    public List<TodoItem> snapshot() {
        return List.copyOf(items);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    /**
     * 生成 system prompt 中的"当前任务清单"区段。
     * 列表为空时返回空串，不占用 prompt 空间。
     *
     * 渲染规则：
     * - completed: [x] + content
     * - in_progress: [~] + activeForm（"Creating index.html"）
     * - pending: [ ] + content
     */
    public String toPromptSection() {
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前任务清单\n\n");
        for (TodoItem item : items) {
            String marker = switch (item.status()) {
                case TodoItem.COMPLETED -> "[x]";
                case TodoItem.IN_PROGRESS -> "[~]";
                default -> "[ ]";
            };
            sb.append(marker).append(" ");
            sb.append(TodoItem.IN_PROGRESS.equals(item.status()) ? item.activeForm() : item.content());
            sb.append("\n");
        }
        return sb.toString();
    }
}
