package agent.permission;

/**
 * 工具调用权限检查的决策结果。
 *
 * ALLOW — 静默放行
 * DENY  — 直接拒绝，不询问用户
 * ASK   — 交给用户交互确认
 */
public enum Decision {
    ALLOW,
    DENY,
    ASK
}
