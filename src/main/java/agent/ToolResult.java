package agent;

/**
 * 工具执行结果。
 *
 * @param success 是否执行成功
 * @param output  输出内容（成功时为工具返回值，失败时为错误信息）
 */
public record ToolResult(boolean success, String output) {

    public static ToolResult success(String output) {
        return new ToolResult(true, output);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, "ERROR: " + message);
    }
}
