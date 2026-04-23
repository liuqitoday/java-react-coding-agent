package agent.render;

import agent.todo.TodoList;
import agent.tools.ToolResult;

/**
 * 终端渲染器：使用 ANSI 转义码为 ReAct 各步骤输出不同颜色。
 *
 * 颜色方案：
 * - [Thought]  青色    LLM 的思考过程
 * - [Action]   黄色    即将调用的工具和参数
 * - [Observe]  绿色    工具执行的返回结果
 * - [Answer]   紫色    最终回答
 * - [Error]    红色    错误信息
 */
public class ConsoleRenderer {

    // ANSI 转义码常量
    private static final String RESET   = "\033[0m";
    private static final String BOLD    = "\033[1m";
    private static final String CYAN    = "\033[36m";
    private static final String YELLOW  = "\033[33m";
    private static final String GREEN   = "\033[32m";
    private static final String MAGENTA = "\033[35m";
    private static final String RED     = "\033[31m";
    private static final String DIM     = "\033[2m";

    public void renderThought(String thought) {
        if (thought != null && !thought.isBlank()) {
            System.out.println(CYAN + BOLD + "[Thought] " + RESET + CYAN + thought + RESET);
        }
    }

    public void renderAction(String toolName, String args) {
        System.out.println(YELLOW + BOLD + "[Action]  " + RESET + YELLOW + toolName + RESET + DIM + "(" + args + ")" + RESET);
    }

    public void renderObservation(String toolName, ToolResult result) {
        String color = result.success() ? GREEN : RED;
        String status = result.success() ? "ok" : "error";
        String content = sanitizeObservation(result.output(), result.success());

        System.out.println(color + BOLD + "[Observe] " + RESET + color + toolName + " [" + status + "]" + RESET);
        for (String line : content.split("\n", -1)) {
            System.out.println(color + "          " + line + RESET);
        }
    }

    public void renderTodo(TodoList todoList) {
        System.out.println(BOLD + "[Todo]    " + RESET + (todoList.isEmpty() ? "（空）" : ""));
        if (todoList.isEmpty()) {
            return;
        }
        for (String line : todoList.renderItems().split("\n")) {
            System.out.println("          " + line);
        }
    }

    public void renderFinalAnswer(String answer) {
        System.out.println();
        System.out.println(MAGENTA + BOLD + "[Answer]  " + RESET + answer);
    }

    public void renderPermissionPrompt(String toolName, String argsPreview) {
        System.out.println();
        System.out.println(MAGENTA + BOLD + "[审批]    " + RESET + MAGENTA
                + "即将执行工具：" + toolName + RESET);
        System.out.println(DIM + "参数：" + argsPreview + RESET);
        System.out.print(BOLD + "允许吗？(y/N): " + RESET);
    }

    public void renderPermissionDenial(String reason) {
        System.out.println(RED + BOLD + "[拒绝]    " + RESET + RED + reason + RESET);
    }

    public void renderError(String error) {
        System.out.println(RED + BOLD + "[Error]   " + RESET + RED + error + RESET);
    }

    public void renderIterationWarning(int max) {
        System.out.println(RED + BOLD + "[Warning] " + RESET + RED + "已达到最大迭代次数（" + max + "），强制终止。" + RESET);
    }

    public void renderSeparator() {
        System.out.println(DIM + "─".repeat(60) + RESET);
    }

    private String sanitizeObservation(String output, boolean success) {
        if (output == null || output.isBlank()) {
            return "（无输出）";
        }

        if (!success && output.startsWith("ERROR: ")) {
            return output.substring("ERROR: ".length());
        }
        return output;
    }
}
