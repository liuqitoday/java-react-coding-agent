package agent.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 编辑文件工具：在已有文件中精准替换一段文本，保留其他内容不变。
 *
 * 相比 write_file（全量重写），Edit 工具的优势：
 * - Token 成本低：只传要改的那一段，不需要把整个文件送给 LLM 再回传
 * - 风险小：LLM 不会因复述整个文件而漏改或误改无关代码
 * - 可验证：old_string 必须精确匹配文件现有内容，否则直接拒绝
 *
 * 核心安全机制：当 replace_all=false 时，old_string 必须在文件中唯一出现。
 * 多次出现时要求 LLM 补充更多上下文来定位——这是防止误改其他相似代码段的关键。
 *
 * 参数：
 * - path:        要编辑的文件路径
 * - old_string:  要被替换的原始文本（必须精确匹配，包括空白字符）
 * - new_string:  替换后的新文本
 * - replace_all: 是否替换所有匹配项，默认 false
 */
public class EditFileTool implements Tool {

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "Edit an existing file by replacing old_string with new_string. "
                + "The old_string must match file content exactly, including whitespace and indentation. "
                + "By default old_string must appear exactly once in the file; "
                + "set replace_all=true to replace every occurrence. "
                + "Use write_file instead to create a new file.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject pathProp = new JsonObject();
        pathProp.addProperty("type", "string");
        pathProp.addProperty("description", "The file path to edit");
        properties.add("path", pathProp);

        JsonObject oldProp = new JsonObject();
        oldProp.addProperty("type", "string");
        oldProp.addProperty("description",
                "The exact text to replace. Must match the file content verbatim, "
                        + "including all whitespace and indentation. "
                        + "Must be unique in the file unless replace_all is true.");
        properties.add("old_string", oldProp);

        JsonObject newProp = new JsonObject();
        newProp.addProperty("type", "string");
        newProp.addProperty("description", "The text to replace old_string with");
        properties.add("new_string", newProp);

        JsonObject replaceAllProp = new JsonObject();
        replaceAllProp.addProperty("type", "boolean");
        replaceAllProp.addProperty("description",
                "Replace all occurrences of old_string. Default: false.");
        properties.add("replace_all", replaceAllProp);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("path");
        required.add("old_string");
        required.add("new_string");
        schema.add("required", required);

        return schema;
    }

    @Override
    public ToolResult execute(JsonObject args) {
        String filePath = args.get("path").getAsString();
        String oldString = args.get("old_string").getAsString();
        String newString = args.get("new_string").getAsString();
        boolean replaceAll = args.has("replace_all") && args.get("replace_all").getAsBoolean();

        // 基础参数校验
        if (oldString.isEmpty()) {
            return ToolResult.error("old_string 不能为空。如需创建新文件，请使用 write_file 工具。");
        }
        if (oldString.equals(newString)) {
            return ToolResult.error("old_string 与 new_string 完全相同，无需修改。");
        }

        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return ToolResult.error("文件不存在：" + filePath
                        + "。Edit 只能修改已存在的文件，创建新文件请用 write_file。");
            }
            if (Files.isDirectory(path)) {
                return ToolResult.error("路径是一个目录，不是文件：" + filePath);
            }

            String content = Files.readString(path);

            // 核心安全检查：确认 old_string 出现次数是否符合预期
            int occurrences = countOccurrences(content, oldString);
            if (occurrences == 0) {
                return ToolResult.error("在文件中未找到 old_string。"
                        + "请检查是否存在空白字符（空格/Tab/换行）或引号等差异，"
                        + "建议先用 read_file 确认待替换内容的精确形式。");
            }
            if (occurrences > 1 && !replaceAll) {
                return ToolResult.error("old_string 在文件中出现了 " + occurrences + " 次，无法唯一定位。"
                        + "请在 old_string 中补充更多上下文使其唯一，或设置 replace_all=true 替换所有匹配。");
            }

            // 执行替换
            String newContent;
            int replacedCount;
            if (replaceAll) {
                newContent = content.replace(oldString, newString);
                replacedCount = occurrences;
            } else {
                // 唯一匹配：手动 indexOf + substring 拼接，避免 String.replace 的正则语义
                int idx = content.indexOf(oldString);
                newContent = content.substring(0, idx) + newString + content.substring(idx + oldString.length());
                replacedCount = 1;
            }

            Files.writeString(path, newContent);

            return ToolResult.success("文件编辑成功：" + filePath
                    + "（替换了 " + replacedCount + " 处，"
                    + content.length() + " → " + newContent.length() + " 字符）");
        } catch (Exception e) {
            return ToolResult.error("编辑文件失败：" + e.getMessage());
        }
    }

    /** 计算子串在文本中的非重叠出现次数。 */
    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
