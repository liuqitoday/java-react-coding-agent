package agent.tools;

import agent.ToolResult;
import com.google.gson.JsonObject;

/**
 * 工具接口：所有工具必须实现此接口。
 *
 * 每个工具需要提供：
 * - name()            工具名称，用于 function calling 中的函数名（如 "read_file"）
 * - description()     工具描述，帮助 LLM 理解工具的用途和使用场景
 * - parameterSchema() 参数的 JSON Schema，告诉 LLM 调用时需要传哪些参数
 * - execute()         实际执行逻辑，接收解析后的参数 JSON，返回 ToolResult
 */
public interface Tool {

    String name();

    String description();

    /**
     * 返回参数的 JSON Schema。
     * 格式要求：{ "type": "object", "properties": {...}, "required": [...] }
     */
    JsonObject parameterSchema();

    ToolResult execute(JsonObject args);
}
