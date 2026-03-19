package agent;

import agent.tools.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具注册中心。
 *
 * 负责三件事：
 * 1. 注册所有可用工具（在构造函数中完成）
 * 2. 生成 API 请求所需的 tools JSON 数组（toJsonArray）
 * 3. 根据工具名称分发执行（execute）
 *
 * 扩展新工具时，只需实现 Tool 接口，然后在构造函数中调用 register() 即可。
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry() {
        register(new ReadFileTool());
        register(new WriteFileTool());
        register(new ListFilesTool());
        register(new ExecuteCommandTool());
    }

    private void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /** 生成 API 请求的 tools 字段：将所有工具转为 OpenAI function schema 格式。 */
    public JsonArray toJsonArray() {
        JsonArray array = new JsonArray();
        for (Tool tool : tools.values()) {
            array.add(ToolDefinition.toFunctionSchema(tool));
        }
        return array;
    }

    /** 根据工具名称查找并执行，返回执行结果。 */
    public ToolResult execute(String toolName, JsonObject args) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            return ToolResult.error("未知工具：" + toolName);
        }
        return tool.execute(args);
    }
}
