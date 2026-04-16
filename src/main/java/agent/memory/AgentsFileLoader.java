package agent.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 发现并加载项目中的 AGENTS.md 文件。
 *
 * 遵循 AGENTS.md 开放标准的文件发现规则：
 * 1. 从 git root 到 CWD 逐级扫描
 * 2. 每级优先 AGENTS.override.md，其次 AGENTS.md
 * 3. 按根→叶顺序拼接，越近的文件优先级越高（可覆盖远处指令）
 * 4. 总大小不超过 MAX_TOTAL_BYTES (32KB)
 *
 * @see <a href="https://agents.md">AGENTS.md 官方规范</a>
 * @see <a href="https://developers.openai.com/codex/guides/agents-md">OpenAI Codex 实现参考</a>
 */
public class AgentsFileLoader {

    /** 总大小限制：32KB（与 OpenAI Codex 的 project_doc_max_bytes 一致） */
    private static final int MAX_TOTAL_BYTES = 32 * 1024;

    /** 每级目录按优先级检查的文件名 */
    private static final String[] FILENAMES = {"AGENTS.override.md", "AGENTS.md"};

    /**
     * 从当前工作目录扫描 AGENTS.md 文件，返回项目上下文。
     *
     * 扫描范围：从 git root 到 CWD 的每一级目录。
     * 如果找不到 git root，则只扫描 CWD。
     */
    public ProjectContext load() {
        Path cwd = Path.of("").toAbsolutePath();
        Path gitRoot = findGitRoot(cwd);

        // 收集从 root 到 cwd 路径上的所有目录（根→叶顺序）
        List<Path> dirsToScan = collectDirectories(gitRoot != null ? gitRoot : cwd, cwd);

        List<Path> loadedFiles = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        boolean truncated = false;
        int totalBytes = 0;

        for (Path dir : dirsToScan) {
            Path file = findAgentsFile(dir);
            if (file == null) {
                continue;
            }

            String text;
            try {
                text = Files.readString(file).strip();
            } catch (IOException e) {
                System.err.println("警告：读取 " + file + " 失败：" + e.getMessage());
                continue;
            }

            if (text.isEmpty()) {
                continue;
            }

            // 检查大小限制
            int textBytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (totalBytes + textBytes > MAX_TOTAL_BYTES) {
                int remaining = MAX_TOTAL_BYTES - totalBytes;
                if (remaining > 0) {
                    // 截断到剩余空间
                    text = truncateToBytes(text, remaining);
                    content.append(text).append("\n\n");
                    loadedFiles.add(file);
                }
                truncated = true;
                break;
            }

            // 多个文件之间用空行分隔
            if (content.length() > 0) {
                content.append("\n\n");
            }
            content.append(text);
            loadedFiles.add(file);
            totalBytes += textBytes;
        }

        return new ProjectContext(content.toString().strip(), loadedFiles, truncated);
    }

    /**
     * 在指定目录中查找 AGENTS.md 文件。
     * 优先级：AGENTS.override.md > AGENTS.md，第一个非空文件胜出。
     */
    private Path findAgentsFile(Path dir) {
        for (String filename : FILENAMES) {
            Path file = dir.resolve(filename);
            if (Files.isRegularFile(file)) {
                try {
                    if (Files.size(file) > 0) {
                        return file;
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 从 root 到 target 收集路径上所有目录（包含两端）。
     * 保证根→叶顺序。
     */
    private List<Path> collectDirectories(Path root, Path target) {
        List<Path> dirs = new ArrayList<>();
        Path normalized = target.normalize();
        Path normalizedRoot = root.normalize();

        // 从 target 向上收集到 root
        Path current = normalized;
        while (current != null && current.startsWith(normalizedRoot)) {
            dirs.add(0, current); // 插入到头部，保证根→叶顺序
            if (current.equals(normalizedRoot)) {
                break;
            }
            current = current.getParent();
        }

        return dirs;
    }

    /**
     * 向上遍历目录树查找 git root（包含 .git 的目录）。
     * 找不到返回 null。
     */
    private Path findGitRoot(Path from) {
        Path current = from.normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /** 将字符串截断到不超过 maxBytes 的 UTF-8 字节数 */
    private String truncateToBytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        // 逐字符截断，避免截断 UTF-8 多字节字符
        StringBuilder sb = new StringBuilder();
        int currentBytes = 0;
        for (int i = 0; i < text.length(); i++) {
            int charBytes = String.valueOf(text.charAt(i)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (currentBytes + charBytes > maxBytes) {
                break;
            }
            sb.append(text.charAt(i));
            currentBytes += charBytes;
        }
        return sb.toString();
    }
}
