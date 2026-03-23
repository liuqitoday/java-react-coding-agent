package agent;

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
        return props.getProperty("api.base-url", "https://api.openai.com/v1");
    }

    public String model() {
        return props.getProperty("api.model", "gpt-4o");
    }

    public int maxIterations() {
        return Integer.parseInt(props.getProperty("agent.max-iterations", "15"));
    }
}
