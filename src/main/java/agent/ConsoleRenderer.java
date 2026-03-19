package agent;

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

    public void renderObservation(String result) {
        // 多行结果缩进对齐，使输出更整齐
        String indented = result.replace("\n", "\n           ");
        System.out.println(GREEN + BOLD + "[Observe] " + RESET + GREEN + indented + RESET);
    }

    public void renderFinalAnswer(String answer) {
        System.out.println();
        System.out.println(MAGENTA + BOLD + "[Answer]  " + RESET + answer);
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
}
