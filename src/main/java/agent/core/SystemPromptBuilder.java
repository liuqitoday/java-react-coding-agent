package agent.core;

import agent.memory.ProjectContext;
import agent.skills.SkillDescriptor;
import agent.skills.SkillRegistry;

/**
 * 静态 System Prompt 组装器。
 *
 * 【核心原则】System prompt 在 Session 内保持恒定——只放"启动就确定、Session 里不会变"的内容：
 * 1. 基础系统提示词（来自 {@link SystemPrompt#BASE}）
 * 2. 项目上下文（AGENTS.md，启动时加载）
 * 3. 可用 skill 目录摘要（启动时扫描的名称 + description，不含激活状态）
 *
 * 【为什么不放 TodoList / 已激活 skill 这类动态内容】
 * Prompt Caching 是字节级前缀匹配：system message 的前缀一旦变化，它及其后所有消息的
 * KV-Cache 都会失效。动态状态必须走"每轮新增的 tool_result"路径——见 {@link StateReminder}。
 *
 * 这个类返回的字符串应该**全 Session 只构建一次**；作为不变量注入到 ConversationHistory
 * 的 system message，后续不再更新。
 */
public class SystemPromptBuilder {

    private final String basePrompt;
    private final ProjectContext projectContext;
    private final SkillRegistry skillRegistry;

    public SystemPromptBuilder(String basePrompt, ProjectContext projectContext,
                               SkillRegistry skillRegistry) {
        this.basePrompt = basePrompt;
        this.projectContext = projectContext;
        this.skillRegistry = skillRegistry;
    }

    /**
     * 组装完整的 system prompt。
     * 在当前版本中，同一个 Builder 实例的每次 build() 都会返回内容相同的字符串——
     * 这是 cache 命中的前提条件。
     */
    public String build() {
        StringBuilder sb = new StringBuilder();

        sb.append(basePrompt);

        // 项目上下文（AGENTS.md，Session 内静态）
        if (!projectContext.isEmpty()) {
            sb.append("\n\n## 项目上下文\n\n");
            sb.append(projectContext.content());
            if (projectContext.truncated()) {
                sb.append("\n\n（注意：项目上下文因超过 32KB 限制已被截断）");
            }
        }

        // 可用 skill 目录（启动扫描，Session 内静态；激活状态不在这里，它由 StateReminder 注入）
        if (!skillRegistry.isEmpty()) {
            sb.append("\n\n");
            sb.append("你当前可用的 skills:\n");
            for (SkillDescriptor desc : skillRegistry.getAll()) {
                sb.append(desc.toCatalogEntry()).append("\n");
            }
            sb.append("\n");
            sb.append("如果任务与某个 skill 的描述匹配，请调用 activate_skill 工具来加载该 skill 的详细指令，然后按照指令执行。");
        }

        return sb.toString();
    }
}
