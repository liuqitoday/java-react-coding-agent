package agent;

import agent.tools.Tool;
import com.google.gson.JsonObject;

/**
 * 将 Tool 接口转换为 OpenAI Function Calling 的 schema 格式。
 *
 * 生成的 JSON 结构：
 * {
 *   "type": "function",
 *   "function": {
 *     "name": "工具名",
 *     "description": "工具描述",
 *     "parameters": { "type": "object", "properties": {...}, "required": [...] }
 *   }
 * }
 *
 * 这个结构会放入 API 请求的 tools 数组中，告诉 LLM 可以调用哪些工具。
 */
public class ToolDefinition {

    public static JsonObject toFunctionSchema(Tool tool) {
        JsonObject function = new JsonObject();
        function.addProperty("name", tool.name());
        function.addProperty("description", tool.description());
        function.add("parameters", tool.parameterSchema());

        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("type", "function");
        wrapper.add("function", function);
        return wrapper;
    }
}
