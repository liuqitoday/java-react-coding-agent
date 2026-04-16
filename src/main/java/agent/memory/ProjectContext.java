package agent.memory;

import java.nio.file.Path;
import java.util.List;

/**
 * 项目级上下文（只读）。
 *
 * 包含从 AGENTS.md 文件中加载的内容，用于注入到 system prompt 中。
 * 遵循 AGENTS.md 开放标准（Linux Foundation / Agentic AI Foundation）。
 *
 * @param content     拼接后的完整内容（根→叶顺序）
 * @param loadedFiles 已加载的文件路径列表
 * @param truncated   是否因超过 32KB 限制而截断
 */
public record ProjectContext(
        String content,
        List<Path> loadedFiles,
        boolean truncated
) {
    /** 是否没有加载到任何项目上下文 */
    public boolean isEmpty() {
        return content.isBlank();
    }

    /** 返回空的项目上下文 */
    public static ProjectContext empty() {
        return new ProjectContext("", List.of(), false);
    }
}
