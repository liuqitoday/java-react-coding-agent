package agent.tools;

import agent.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 执行命令工具：通过 /bin/sh -c 执行 shell 命令。
 * 设有 30 秒超时保护，防止长时间运行的命令阻塞 Agent。
 * 标准输出和标准错误合并返回。
 */
public class ExecuteCommandTool implements Tool {

    private static final int TIMEOUT_SECONDS = 30;

    @Override
    public String name() {
        return "execute_command";
    }

    @Override
    public String description() {
        return "Execute a shell command and return its output. Commands have a 30-second timeout.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonObject commandProp = new JsonObject();
        commandProp.addProperty("type", "string");
        commandProp.addProperty("description", "The shell command to execute");
        properties.add("command", commandProp);
        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("command");
        schema.add("required", required);

        return schema;
    }

    @Override
    public ToolResult execute(JsonObject args) {
        String command = args.get("command").getAsString();
        try {
            // 使用 /bin/sh -c 执行命令，合并 stdout 和 stderr
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取命令输出
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            // 等待进程结束，超时则强制终止
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("命令超时（" + TIMEOUT_SECONDS + " 秒）");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return ToolResult.success("退出码: " + exitCode + "\n" + output);
            }
            return ToolResult.success(output);
        } catch (Exception e) {
            return ToolResult.error("执行命令失败：" + e.getMessage());
        }
    }
}
