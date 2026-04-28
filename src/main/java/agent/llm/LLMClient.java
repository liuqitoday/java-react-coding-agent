package agent.llm;

import agent.config.AgentConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
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
    private final int maxRetryAttempts;
    private final long initialRetryDelayMs;
    private final long maxRetryDelayMs;

    public LLMClient(AgentConfig config, LLMLogger logger) {
        this.apiKey = config.apiKey();
        this.baseUrl = config.baseUrl();
        this.model = config.model();
        this.logger = logger;
        this.maxRetryAttempts = config.apiRetryMaxAttempts();
        this.initialRetryDelayMs = config.apiRetryInitialDelayMs();
        this.maxRetryDelayMs = Math.max(initialRetryDelayMs, config.apiRetryMaxDelayMs());
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

        HttpResponse<String> response = sendWithRetry(request);

        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) throws Exception {
        long delayMs = initialRetryDelayMs;

        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                logger.logResponse(response.statusCode(), response.body(), attempt, maxRetryAttempts);

                if (response.statusCode() == 200) {
                    return response;
                }

                String errorMessage = buildHttpErrorMessage(response);
                logger.logError("第 " + attempt + "/" + maxRetryAttempts + " 次尝试返回 HTTP "
                        + response.statusCode());

                if (!shouldRetryStatus(response.statusCode()) || attempt == maxRetryAttempts) {
                    throw new RuntimeException(errorMessage);
                }

                logger.logRetry(attempt + 1, maxRetryAttempts, delayMs,
                        "收到可重试状态码 HTTP " + response.statusCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.logError("请求在第 " + attempt + "/" + maxRetryAttempts + " 次尝试时被中断: "
                        + e.getMessage());
                throw e;
            } catch (IOException e) {
                logger.logError("第 " + attempt + "/" + maxRetryAttempts + " 次尝试异常: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());

                if (attempt == maxRetryAttempts) {
                    throw e;
                }

                logger.logRetry(attempt + 1, maxRetryAttempts, delayMs,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }

            sleepBeforeRetry(delayMs);
            delayMs = nextDelay(delayMs);
        }

        throw new IllegalStateException("LLM 请求重试逻辑异常退出");
    }

    private boolean shouldRetryStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private String buildHttpErrorMessage(HttpResponse<String> response) {
        return "API 请求失败 (HTTP " + response.statusCode() + "): " + response.body();
    }

    private void sleepBeforeRetry(long delayMs) throws InterruptedException {
        if (delayMs <= 0) {
            return;
        }
        Thread.sleep(delayMs);
    }

    private long nextDelay(long currentDelayMs) {
        if (currentDelayMs <= 0) {
            return 0;
        }
        long doubledDelay = currentDelayMs * 2;
        if (doubledDelay < 0) {
            return maxRetryDelayMs;
        }
        return Math.min(doubledDelay, maxRetryDelayMs);
    }
}
