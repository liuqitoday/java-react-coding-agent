package agent.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Skill 注册中心：负责发现、解析、索引所有可用 skill。
 *
 * 启动时扫描当前目录下的 .agents/skills/，只读取每个 skill 的
 * YAML frontmatter（metadata），不读取 SKILL.md 正文。
 */
public class SkillRegistry {

    private final Map<String, SkillDescriptor> skills = new LinkedHashMap<>();

    /**
     * 扫描 <cwd>/.agents/skills/ 目录，建立 skill 索引。
     */
    public void scan() {
        Path skillsDir = Path.of(System.getProperty("user.dir"), ".agents", "skills");
        if (!Files.isDirectory(skillsDir)) {
            return;
        }

        try (Stream<Path> entries = Files.list(skillsDir)) {
            entries.filter(Files::isDirectory)
                    .sorted()
                    .forEach(skillDir -> {
                        SkillDescriptor desc = parseSkill(skillDir);
                        if (desc != null) {
                            skills.put(desc.name(), desc);
                        }
                    });
        } catch (IOException e) {
            System.err.println("警告：扫描 skill 目录失败：" + skillsDir + " - " + e.getMessage());
        }
    }

    /**
     * 解析单个 skill 目录，只读取 frontmatter。
     */
    private SkillDescriptor parseSkill(Path skillDir) {
        Path skillMd = skillDir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillMd)) {
            return null;
        }

        String content;
        try {
            content = Files.readString(skillMd);
        } catch (IOException e) {
            System.err.println("警告：读取 SKILL.md 失败：" + skillMd + " - " + e.getMessage());
            return null;
        }

        FrontmatterParser parsed = FrontmatterParser.parse(content);
        Map<String, String> fm = parsed.frontmatter();

        String name = fm.get("name");
        String description = fm.get("description");

        if (name == null || name.isBlank()) {
            System.err.println("警告：skill 缺少 name 字段：" + skillDir);
            return null;
        }
        if (description == null || description.isBlank()) {
            System.err.println("警告：skill 缺少 description 字段：" + skillDir);
            return null;
        }

        return new SkillDescriptor(name, description, skillDir);
    }

    /** 获取所有已索引的 skill。 */
    public Collection<SkillDescriptor> getAll() {
        return Collections.unmodifiableCollection(skills.values());
    }

    /** 按名称查找 skill。 */
    public SkillDescriptor get(String name) {
        return skills.get(name);
    }

    /** 是否有任何已索引的 skill。 */
    public boolean isEmpty() {
        return skills.isEmpty();
    }

    /** 已索引的 skill 数量。 */
    public int size() {
        return skills.size();
    }
}
