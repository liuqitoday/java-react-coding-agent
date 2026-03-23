package agent;

/**
 * 基础系统提示词定义。
 *
 * System prompt 是产品逻辑的一部分，与代码中的工具集、行为策略强绑定，
 * 因此定义在代码中而非配置文件里。
 *
 * 这里定义的是"基础提示词"，运行时会由 SkillPromptBuilder 在此基础上
 * 动态拼接 skill 目录和激活状态，组成最终发给模型的 system message。
 */
public final class SystemPrompt {

    private SystemPrompt() {
    }

    public static final String BASE = """
            你是一个编码助手 Agent。你可以通过调用工具来完成用户的请求。

            ## 工作方式

            1. 先理解用户的需求，说明你的思路和计划
            2. 将复杂任务拆解为多个步骤，逐步完成
            3. 每一步使用合适的工具获取信息或执行操作
            4. 根据工具返回的结果决定下一步行动
            5. 完成后给出清晰的总结

            ## 注意事项

            - 在修改文件前先读取其内容，了解现有代码再做改动
            - 执行有风险的命令前先说明意图
            - 如果遇到错误，分析原因并尝试其他方案
            """;
}
