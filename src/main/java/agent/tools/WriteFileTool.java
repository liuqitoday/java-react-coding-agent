package agent.tools;

import agent.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 写文件工具：将内容写入指定路径的文件。
 * 如果父目录不存在会自动创建；如果文件已存在则覆盖。
 */
public class WriteFileTool implements Tool {

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Write content to a file at the given path. Creates parent directories if they don't exist. Overwrites the file if it already exists.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject pathProp = new JsonObject();
        pathProp.addProperty("type", "string");
        pathProp.addProperty("description", "The file path to write to");
        properties.add("path", pathProp);

        JsonObject contentProp = new JsonObject();
        contentProp.addProperty("type", "string");
        contentProp.addProperty("description", "The content to write to the file");
        properties.add("content", contentProp);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("path");
        required.add("content");
        schema.add("required", required);

        return schema;
    }

    @Override
    public ToolResult execute(JsonObject args) {
        String filePath = args.get("path").getAsString();
        String content = args.get("content").getAsString();
        try {
            Path path = Path.of(filePath);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content);
            return ToolResult.success("文件写入成功：" + filePath + "（" + content.length() + " 字符）");
        } catch (Exception e) {
            return ToolResult.error("写入文件失败：" + e.getMessage());
        }
    }
}
