package agent.skills;

import java.nio.file.Path;

/**
 * Skill 的元数据描述。
 *
 * 对应 SKILL.md 中 YAML frontmatter 的解析结果。
 * 启动时只加载 metadata（~100 tokens），不读取 SKILL.md 正文。
 *
 * @param name        skill 名称
 * @param description skill 描述，说明做什么以及何时使用
 * @param rootPath    skill 根目录路径
 */
public record SkillDescriptor(
        String name,
        String description,
        Path rootPath
) {
    /**
     * 生成供 system prompt 使用的单行摘要。
     * 格式："- name: description"
     */
    public String toCatalogEntry() {
        return "- " + name + ": " + description;
    }
}
