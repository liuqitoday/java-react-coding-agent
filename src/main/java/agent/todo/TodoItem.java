package agent.todo;

/**
 * 一条待办事项。
 *
 * @param content    任务描述（祈使形式，如 "Create index.html"）
 * @param activeForm 正在进行时的描述（如 "Creating index.html"）——LLM 要多想一层
 * @param status     pending / in_progress / completed
 */
public record TodoItem(String content, String activeForm, String status) {

    public static final String PENDING = "pending";
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";

    /** status 是否为合法值。 */
    public boolean isValidStatus() {
        return PENDING.equals(status) || IN_PROGRESS.equals(status) || COMPLETED.equals(status);
    }
}
