package agent.tools;

import agent.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 列目录工具：列出指定路径下的文件和子目录。
 * 支持 recursive 参数进行递归列举。
 * 目录名前加 [DIR] 前缀以区分文件和目录。
 */
public class ListFilesTool implements Tool {

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String description() {
        return "List files and directories at the given path. Use recursive=true to list all files recursively.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject pathProp = new JsonObject();
        pathProp.addProperty("type", "string");
        pathProp.addProperty("description", "The directory path to list");
        properties.add("path", pathProp);

        JsonObject recursiveProp = new JsonObject();
        recursiveProp.addProperty("type", "boolean");
        recursiveProp.addProperty("description", "Whether to list files recursively (default: false)");
        properties.add("recursive", recursiveProp);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("path");
        schema.add("required", required);

        return schema;
    }

    @Override
    public ToolResult execute(JsonObject args) {
        String dirPath = args.get("path").getAsString();
        boolean recursive = args.has("recursive") && args.get("recursive").getAsBoolean();
        try {
            Path path = Path.of(dirPath);
            if (!Files.exists(path)) {
                return ToolResult.error("路径不存在：" + dirPath);
            }
            if (!Files.isDirectory(path)) {
                return ToolResult.error("路径不是目录：" + dirPath);
            }

            // recursive 为 true 时用 Files.walk 递归遍历，否则用 Files.list 只列一层
            Stream<Path> stream = recursive ? Files.walk(path) : Files.list(path);
            String result;
            try (stream) {
                result = stream
                        .filter(p -> !p.equals(path)) // 排除根目录自身
                        .map(p -> {
                            String relative = path.relativize(p).toString();
                            return Files.isDirectory(p) ? "[DIR]  " + relative : "       " + relative;
                        })
                        .sorted()
                        .collect(Collectors.joining("\n"));
            }

            if (result.isEmpty()) {
                return ToolResult.success("（空目录）");
            }
            return ToolResult.success(result);
        } catch (IOException e) {
            return ToolResult.error("列出文件失败：" + e.getMessage());
        }
    }
}
