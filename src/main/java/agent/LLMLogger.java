package agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * LLM 交互日志记录器。
 *
 * 将每次 API 请求和响应的完整 JSON 写入日志文件，方便学习和调试。
 * 每次启动程序会在 logs/ 目录下创建一个新的日志文件（按启动时间命名）。
 * JSON 使用 pretty-print 格式输出，便于人工阅读。
 */
public class LLMLogger {

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path logFile;
    private final Gson prettyGson;
    private int requestCount = 0;

    public LLMLogger() {
        this.prettyGson = new GsonBuilder().setPrettyPrinting().create();

        // 创建 logs 目录
        Path logDir = Path.of("logs");
        try {
            Files.createDirectories(logDir);
        } catch (IOException e) {
            System.err.println("警告：创建 logs 目录失败：" + e.getMessage());
        }

        // 日志文件名格式：llm_20260318_172914.log
        String fileName = "llm_" + LocalDateTime.now().format(FILE_FMT) + ".log";
        this.logFile = logDir.resolve(fileName);

        write("=".repeat(70));
        write("LLM 交互日志 — 会话开始于 " + LocalDateTime.now().format(TIMESTAMP_FMT));
        write("=".repeat(70));
    }

    /** 记录请求体（发送 API 请求之前调用）。 */
    public void logRequest(JsonObject requestBody) {
        requestCount++;
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);

        write("");
        write("┌─ 请求 #" + requestCount + "  [" + timestamp + "] ─────────────────────────");
        write(prettyGson.toJson(requestBody));
        write("└─ 请求 #" + requestCount + " 结束 ──────────────────────────────────────");
    }

    /** 记录响应体（收到 API 响应之后调用）。 */
    public void logResponse(int httpStatus, String responseBody) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);

        write("");
        write("┌─ 响应 #" + requestCount + "  [" + timestamp + "]  HTTP " + httpStatus + " ────────");
        // 尝试 pretty-print，如果不是合法 JSON 则原样输出
        try {
            var parsed = prettyGson.fromJson(responseBody, Object.class);
            write(prettyGson.toJson(parsed));
        } catch (Exception e) {
            write(responseBody);
        }
        write("└─ 响应 #" + requestCount + " 结束 ────────────────────────────────────");
    }

    /** 记录 API 调用过程中发生的错误。 */
    public void logError(String error) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        write("");
        write("╳─ 错误 #" + requestCount + "  [" + timestamp + "]: " + error);
    }

    public Path getLogFile() {
        return logFile;
    }

    /** 将一行文本追加写入日志文件。 */
    private void write(String line) {
        try {
            Files.writeString(logFile, line + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 日志写入失败时静默忽略，不影响主流程
        }
    }
}
