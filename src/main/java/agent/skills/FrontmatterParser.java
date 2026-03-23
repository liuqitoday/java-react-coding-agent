package agent.skills;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 简易 YAML Frontmatter 解析器。
 *
 * 解析 SKILL.md 开头 "---" 包围的 YAML 块，提取键值对。
 * 不引入外部 YAML 库，只处理 Agent Skills 规范需要的格式：
 * - 普通键值对：key: value
 * - 带引号的值：key: "value" 或 key: 'value'
 * - 折叠块标量：key: >-（后续缩进行拼接为单行）
 * - 字面块标量：key: |（后续缩进行保留换行）
 *
 * 解析结果分为两部分：
 * - frontmatter：所有键值对的 Map
 * - body：frontmatter 之后的 SKILL.md 正文
 */
public class FrontmatterParser {

    private final Map<String, String> frontmatter;
    private final String body;

    private FrontmatterParser(Map<String, String> frontmatter, String body) {
        this.frontmatter = frontmatter;
        this.body = body;
    }

    public Map<String, String> frontmatter() {
        return frontmatter;
    }

    public String body() {
        return body;
    }

    /**
     * 解析 SKILL.md 的完整内容。
     *
     * @param content SKILL.md 的文本内容
     * @return 解析结果，包含 frontmatter 和 body
     */
    public static FrontmatterParser parse(String content) {
        Map<String, String> fm = new LinkedHashMap<>();

        if (content == null || content.isBlank()) {
            return new FrontmatterParser(fm, "");
        }

        String[] lines = content.split("\n", -1);
        int lineIndex = 0;

        // 跳过空行
        while (lineIndex < lines.length && lines[lineIndex].trim().isEmpty()) {
            lineIndex++;
        }

        // 检查是否以 "---" 开头
        if (lineIndex >= lines.length || !lines[lineIndex].trim().equals("---")) {
            // 没有 frontmatter，全部作为 body
            return new FrontmatterParser(fm, content);
        }
        lineIndex++; // 跳过开头的 "---"

        // 解析 frontmatter 键值对
        String currentKey = null;
        StringBuilder currentValue = null;
        boolean isBlockScalar = false;  // >- 或 |
        boolean isFolded = false;       // >- 为 folded, | 为 literal

        while (lineIndex < lines.length) {
            String line = lines[lineIndex];

            // 遇到结束的 "---"
            if (line.trim().equals("---")) {
                // 保存最后一个键值对
                if (currentKey != null && currentValue != null) {
                    fm.put(currentKey, currentValue.toString().trim());
                }
                lineIndex++;
                break;
            }

            if (isBlockScalar && (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
                // 块标量的续行（以空格/tab 开头或空行）
                String trimmed = line.trim();
                if (currentValue.length() > 0 && !trimmed.isEmpty()) {
                    if (isFolded) {
                        currentValue.append(" ");
                    } else {
                        currentValue.append("\n");
                    }
                }
                currentValue.append(trimmed);
            } else {
                // 非续行：先保存上一个键值对
                if (currentKey != null && currentValue != null) {
                    fm.put(currentKey, currentValue.toString().trim());
                }
                isBlockScalar = false;

                // 解析新的键值对
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    currentKey = line.substring(0, colonIndex).trim();
                    String rawValue = line.substring(colonIndex + 1).trim();

                    if (rawValue.equals(">-") || rawValue.equals(">")) {
                        // 折叠块标量
                        isBlockScalar = true;
                        isFolded = true;
                        currentValue = new StringBuilder();
                    } else if (rawValue.equals("|") || rawValue.equals("|-")) {
                        // 字面块标量
                        isBlockScalar = true;
                        isFolded = false;
                        currentValue = new StringBuilder();
                    } else {
                        // 普通值：去除引号
                        currentValue = new StringBuilder(stripQuotes(rawValue));
                    }
                }
            }

            lineIndex++;
        }

        // 如果没有遇到结束的 "---"，也保存最后一个键值对
        if (currentKey != null && currentValue != null && !fm.containsKey(currentKey)) {
            fm.put(currentKey, currentValue.toString().trim());
        }

        // 剩余部分作为 body
        StringBuilder bodyBuilder = new StringBuilder();
        while (lineIndex < lines.length) {
            if (bodyBuilder.length() > 0) {
                bodyBuilder.append("\n");
            }
            bodyBuilder.append(lines[lineIndex]);
            lineIndex++;
        }

        return new FrontmatterParser(fm, bodyBuilder.toString().trim());
    }

    /** 去除字符串两端的引号（单引号或双引号）。 */
    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
