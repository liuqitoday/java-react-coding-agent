package agent.permission;

import agent.render.ConsoleRenderer;
import com.google.gson.JsonObject;

import java.util.Scanner;

/**
 * 权限闸门：在 ReActLoop 调用工具前做前置校验。
 *
 * 流程：
 * 1. 调用 {@link PermissionPolicy#check} 得到 {@link Decision}
 * 2. ALLOW  → 返回 null（放行）
 * 3. DENY   → 渲染拒绝提示，返回拒绝原因字符串
 * 4. ASK    → 打印审批提示、读 stdin；输入 {@code y}/{@code yes} 放行，其他拒绝
 *
 * 【调用约定】返回 null = 放行；返回非 null = 拒绝，字符串内容就是要写回给 LLM 的错误。
 *
 * 【Scanner 复用】必须和 {@link agent.Main} 的 REPL 使用同一个 Scanner 实例，
 * 否则会把系统标准输入缓冲区割裂成两份，导致某一方读不到输入。
 */
public class PermissionGate {

    private final PermissionPolicy policy;
    private final ConsoleRenderer renderer;
    private final Scanner stdin;

    public PermissionGate(PermissionPolicy policy, ConsoleRenderer renderer, Scanner stdin) {
        this.policy = policy;
        this.renderer = renderer;
        this.stdin = stdin;
    }

    /**
     * 校验一次工具调用。
     *
     * @return null 表示放行；非 null 表示拒绝，字符串是写回给 LLM 的原因说明
     */
    public String check(String toolName, JsonObject args) {
        Decision decision = policy.check(toolName, args);
        return switch (decision) {
            case ALLOW -> null;
            case DENY -> {
                String reason = "权限策略拒绝了此工具调用：" + toolName
                        + "（参数命中 deny 规则）。请改用更安全的方式，或向用户解释原因。";
                renderer.renderPermissionDenial(reason);
                yield reason;
            }
            case ASK -> askUser(toolName, args);
        };
    }

    private String askUser(String toolName, JsonObject args) {
        renderer.renderPermissionPrompt(toolName, args.toString());
        String line = stdin.hasNextLine() ? stdin.nextLine().trim().toLowerCase() : "";
        if (line.equals("y") || line.equals("yes")) {
            return null;
        }
        String reason = "用户拒绝了此工具调用：" + toolName
                + "。如果任务必须继续，请向用户解释原因并请求确认。";
        renderer.renderPermissionDenial(reason);
        return reason;
    }
}
