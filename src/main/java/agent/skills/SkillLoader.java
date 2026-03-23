package agent.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Skill 内容加载器。
 *
 * 负责在 skill 被激活时读取完整内容：
 * 1. 读取 SKILL.md 正文（frontmatter 之后的部分）
 * 2. 生成 skill 目录下的资源摘要（scripts/、references/、assets/ 的文件列表）
 *
 * 遵循 progressive disclosure 模型：此类只在激活时调用，不在启动时调用。
 */
public class SkillLoader {

    /**
     * Skill 激活后的完整内容。
     *
     * @param body          SKILL.md 正文（不含 frontmatter）
     * @param resourceSummary 目录下的资源文件摘要
     */
    public record SkillContent(String body, String resourceSummary) {
    }

    /**
     * 加载 skill 的完整内容。
     *
     * @param descriptor skill 元数据（包含 rootPath）
     * @return skill 正文和资源摘要
     * @throws IOException 读取失败时抛出
     */
    public SkillContent load(SkillDescriptor descriptor) throws IOException {
        Path skillMd = descriptor.rootPath().resolve("SKILL.md");
        String content = Files.readString(skillMd);

        // 解析出 body（跳过 frontmatter）
        FrontmatterParser parsed = FrontmatterParser.parse(content);
        String body = parsed.body();

        // 生成资源摘要
        String resourceSummary = buildResourceSummary(descriptor.rootPath());

        return new SkillContent(body, resourceSummary);
    }

    /**
     * 扫描 skill 目录下的 scripts/、references/、assets/，生成文件列表摘要。
     */
    private String buildResourceSummary(Path skillRoot) {
        StringBuilder sb = new StringBuilder();

        appendDirectoryListing(sb, skillRoot, "scripts");
        appendDirectoryListing(sb, skillRoot, "references");
        appendDirectoryListing(sb, skillRoot, "assets");

        return sb.toString().trim();
    }

    private void appendDirectoryListing(StringBuilder sb, Path skillRoot, String dirName) {
        Path dir = skillRoot.resolve(dirName);
        if (!Files.isDirectory(dir)) {
            return;
        }

        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .forEach(p -> files.add(dirName + "/" + dir.relativize(p)));
        } catch (IOException e) {
            // 忽略无法读取的目录
            return;
        }

        if (!files.isEmpty()) {
            sb.append(dirName).append("/\n");
            for (String file : files) {
                sb.append("  ").append(file).append("\n");
            }
        }
    }
}
