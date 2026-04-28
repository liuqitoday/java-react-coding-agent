package agent.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 加载 agent.properties 配置文件。
 *
 * 配置项说明：
 * - api.key         API 密钥，留空则从环境变量 OPENAI_API_KEY 读取
 * - api.base-url    API 基础 URL，可替换为兼容的第三方服务
 * - api.model       模型名称（如 gpt-4o、claude-haiku-4-5 等）
 * - api.retry.max-attempts      LLM API 最大尝试次数（包含首次请求）
 * - api.retry.initial-delay-ms  首次重试前等待时间（毫秒）
 * - api.retry.max-delay-ms      指数退避的最大等待时间（毫秒）
 * - agent.max-iterations  ReAct 最大迭代次数，防止无限循环
 *
 * 注意：system prompt 是产品逻辑，不属于用户配置，定义在 {@link SystemPrompt} 中。
 */
public class AgentConfig {

    private final Properties props = new Properties();

    public AgentConfig() {
        // 从当前目录加载配置文件
        Path configPath = Path.of("agent.properties");
        if (Files.exists(configPath)) {
            try (InputStream in = Files.newInputStream(configPath)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("警告：加载 agent.properties 失败：" + e.getMessage());
            }
        }
    }

    public String apiKey() {
        String key = props.getProperty("api.key", "");
        if (key.isBlank() || key.equals("your-api-key-here")) {
            // 配置文件中未设置有效 Key，回退到环境变量
            String envKey = System.getenv("OPENAI_API_KEY");
            return envKey != null ? envKey : "";
        }
        return key;
    }

    public String baseUrl() {
        String url = props.getProperty("api.base-url", "");
        return url.isBlank() ? "https://api.openai.com/v1" : url;
    }

    public String model() {
        return props.getProperty("api.model", "gpt-4o");
    }

    public int apiRetryMaxAttempts() {
        return intProperty("api.retry.max-attempts", 3, 1);
    }

    public long apiRetryInitialDelayMs() {
        return longProperty("api.retry.initial-delay-ms", 1000L, 0L);
    }

    public long apiRetryMaxDelayMs() {
        return longProperty("api.retry.max-delay-ms", 8000L, 0L);
    }

    public int maxIterations() {
        return Integer.parseInt(props.getProperty("agent.max-iterations", "15"));
    }

    private int intProperty(String key, int defaultValue, int minValue) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Math.max(Integer.parseInt(value.trim()), minValue);
        } catch (NumberFormatException e) {
            System.err.println("警告：配置项 " + key + " 不是合法整数，使用默认值 " + defaultValue);
            return defaultValue;
        }
    }

    private long longProperty(String key, long defaultValue, long minValue) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Math.max(Long.parseLong(value.trim()), minValue);
        } catch (NumberFormatException e) {
            System.err.println("警告：配置项 " + key + " 不是合法整数，使用默认值 " + defaultValue);
            return defaultValue;
        }
    }
}
