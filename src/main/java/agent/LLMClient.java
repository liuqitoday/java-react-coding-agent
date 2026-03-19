package agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * LLM 客户端：封装对 OpenAI 兼容 API 的 HTTP 调用。
 *
 * 使用 JDK 内置的 java.net.http.HttpClient，不引入额外 HTTP 库。
 * 请求格式遵循 OpenAI Chat Completions API 规范：
 * POST /v1/chat/completions
 * Body: { "model": "...", "messages": [...], "tools": [...] }
 */
public class LLMClient {

    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final LLMLogger logger;

    public LLMClient(AgentConfig config, LLMLogger logger) {
        this.apiKey = config.apiKey();
        this.baseUrl = config.baseUrl();
        this.model = config.model();
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 发送 Chat Completion 请求，返回解析后的 JSON 响应。
     *
     * @param messages 对话消息数组（包含 system、user、assistant、tool 角色的消息）
     * @param tools    工具定义数组（OpenAI function calling schema 格式）
     * @return API 响应的 JSON 对象，包含 choices[].message
     * @throws Exception 网络错误或 HTTP 非 200 状态码时抛出异常
     */
    public JsonObject chatCompletion(JsonArray messages, JsonArray tools) throws Exception {
        // 构建请求体：model + messages + tools（三个顶层字段）
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        if (tools != null && tools.size() > 0) {
            body.add("tools", tools);
        }

        // 记录请求日志（发送前）
        logger.logRequest(body);

        String requestBody = body.toString();

        // 构建 HTTP 请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        // 发送请求
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.logError(e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }

        // 记录响应日志（收到后）
        logger.logResponse(response.statusCode(), response.body());

        if (response.statusCode() != 200) {
            throw new RuntimeException("API 请求失败 (HTTP " + response.statusCode() + "): " + response.body());
        }

        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
