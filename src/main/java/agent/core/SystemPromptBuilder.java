package agent.core;

import agent.memory.ProjectContext;
import agent.skills.SkillDescriptor;
import agent.skills.SkillRegistry;
import agent.skills.SkillSessionState;

/**
 * 动态 System Prompt 组装器。
 *
 * 每次 LLM 调用前，将以下部分组装为完整的 system message：
 * 1. 基础系统提示词（来自 SystemPrompt.BASE）
 * 2. 项目上下文（来自 AGENTS.md 文件）
 * 3. 可用 skill 目录摘要（来自 SkillRegistry）
 * 4. 当前已激活 skill 的状态（来自 SkillSessionState）
 */
public class SystemPromptBuilder {

    private final String basePrompt;
    private final ProjectContext projectContext;
    private final SkillRegistry skillRegistry;
    private final SkillSessionState sessionState;

    public SystemPromptBuilder(String basePrompt, ProjectContext projectContext,
                               SkillRegistry skillRegistry, SkillSessionState sessionState) {
        this.basePrompt = basePrompt;
        this.projectContext = projectContext;
        this.skillRegistry = skillRegistry;
        this.sessionState = sessionState;
    }

    /**
     * 组装完整的 system prompt。
     */
    public String build() {
        StringBuilder sb = new StringBuilder();

        sb.append(basePrompt);

        // 项目上下文（AGENTS.md）
        if (!projectContext.isEmpty()) {
            sb.append("\n\n## 项目上下文\n\n");
            sb.append(projectContext.content());
            if (projectContext.truncated()) {
                sb.append("\n\n（注意：项目上下文因超过 32KB 限制已被截断）");
            }
        }

        // 可用 skill 目录
        if (!skillRegistry.isEmpty()) {
            sb.append("\n\n");
            sb.append("你当前可用的 skills:\n");
            for (SkillDescriptor desc : skillRegistry.getAll()) {
                sb.append(desc.toCatalogEntry()).append("\n");
            }
            sb.append("\n");
            sb.append("如果任务与某个 skill 的描述匹配，请调用 activate_skill 工具来加载该 skill 的详细指令，然后按照指令执行。");
        }

        // 已激活 skill 状态
        if (sessionState.activeCount() > 0) {
            sb.append("\n\n");
            sb.append("当前已激活的 skills:\n");
            for (String name : sessionState.getActiveSkills()) {
                sb.append("- ").append(name).append("\n");
            }
        }

        return sb.toString();
    }
}
