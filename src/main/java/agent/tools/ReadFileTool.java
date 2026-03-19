package agent.tools;

import agent.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读文件工具：读取指定路径文件的文本内容。
 * 超过 10000 字符时自动截断，防止占用过多 token。
 */
public class ReadFileTool implements Tool {

    private static final int MAX_CHARS = 10000;

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read the contents of a file at the given path. Returns the text content. Files larger than 10000 characters will be truncated.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonObject pathProp = new JsonObject();
        pathProp.addProperty("type", "string");
        pathProp.addProperty("description", "The file path to read");
        properties.add("path", pathProp);
        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("path");
        schema.add("required", required);

        return schema;
    }

    @Override
    public ToolResult execute(JsonObject args) {
        String filePath = args.get("path").getAsString();
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return ToolResult.error("文件不存在：" + filePath);
            }
            if (Files.isDirectory(path)) {
                return ToolResult.error("路径是一个目录，不是文件：" + filePath);
            }
            String content = Files.readString(path);
            if (content.length() > MAX_CHARS) {
                content = content.substring(0, MAX_CHARS)
                        + "\n... [已截断，共 " + content.length() + " 字符]";
            }
            return ToolResult.success(content);
        } catch (Exception e) {
            return ToolResult.error("读取文件失败：" + e.getMessage());
        }
    }
}
