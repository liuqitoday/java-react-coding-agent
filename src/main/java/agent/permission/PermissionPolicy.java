package agent.permission;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 权限策略：从 permissions.json 加载规则，按 deny → ask → allow 优先级匹配工具调用。
 *
 * 【规则格式】每条规则是字符串 {@code "toolName:paramsSubstring"}：
 * - toolName：要匹配的工具名；{@code "*"} 表示任意工具
 * - paramsSubstring：对该调用 args 的 JSON 序列化串做"子串包含"匹配；空串代表匹配任何参数
 *
 * 【示例】
 * <pre>
 * {
 *   "deny": [
 *     "execute_command:rm -rf",
 *     "execute_command:mkfs"
 *   ],
 *   "ask": [
 *     "execute_command:",
 *     "write_file:/etc/"
 *   ]
 * }
 * </pre>
 *
 * 【默认行为】配置文件不存在时返回空策略——所有工具 ALLOW，等同"没启用权限系统"。
 * 这是刻意设计：只有用户主动 {@code cp permissions.json.example permissions.json}
 * 才启用拦截；否则保持现状的演示体验。
 *
 * 【为什么用子串包含而不是 glob 或正则】保持"简单能用"：学习项目的核心是把
 * "PreToolUse → 策略查 → 三态决策"的机制讲清楚；匹配算法是次要细节，后续可替换。
 */
public class PermissionPolicy {

    private final List<Rule> denyRules;
    private final List<Rule> askRules;

    /** 一条解析后的规则。子串为空表示该工具的任何调用都命中。 */
    private record Rule(String toolName, String paramsSubstring) {
        boolean matches(String callToolName, String argsJson) {
            boolean toolMatch = "*".equals(toolName) || toolName.equals(callToolName);
            if (!toolMatch) {
                return false;
            }
            return paramsSubstring.isEmpty() || argsJson.contains(paramsSubstring);
        }
    }

    private PermissionPolicy(List<Rule> denyRules, List<Rule> askRules) {
        this.denyRules = denyRules;
        this.askRules = askRules;
    }

    /** 空策略：所有调用都 ALLOW。用于配置文件不存在或解析失败时的降级。 */
    public static PermissionPolicy empty() {
        return new PermissionPolicy(List.of(), List.of());
    }

    /**
     * 从 permissions.json 加载。
     * 文件不存在 → 返回 {@link #empty()}（正常情况，不打印警告）。
     * 解析错误 → 打印警告、返回 {@link #empty()}（容错：不因配置坏了就启动不了）。
     */
    public static PermissionPolicy load(Path configPath) {
        if (!Files.exists(configPath)) {
            return empty();
        }
        try {
            String content = Files.readString(configPath);
            JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
            return new PermissionPolicy(parseArray(obj, "deny"), parseArray(obj, "ask"));
        } catch (IOException | RuntimeException e) {
            System.err.println("警告：加载 " + configPath + " 失败，将使用空策略：" + e.getMessage());
            return empty();
        }
    }

    private static List<Rule> parseArray(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return List.of();
        }
        JsonArray arr = obj.getAsJsonArray(key);
        List<Rule> rules = new ArrayList<>();
        for (var elem : arr) {
            if (!elem.isJsonPrimitive()) {
                continue;
            }
            String raw = elem.getAsString();
            int sep = raw.indexOf(':');
            if (sep < 0) {
                // 没冒号：只匹配工具名，参数任意
                rules.add(new Rule(raw.trim(), ""));
            } else {
                // 有冒号：冒号左边是工具名，右边是参数子串（不 trim，空格也有意义）
                rules.add(new Rule(raw.substring(0, sep).trim(), raw.substring(sep + 1)));
            }
        }
        return rules;
    }

    /** 按优先级查：deny → ask → allow。 */
    public Decision check(String toolName, JsonObject args) {
        String argsJson = args.toString();
        for (Rule r : denyRules) {
            if (r.matches(toolName, argsJson)) {
                return Decision.DENY;
            }
        }
        for (Rule r : askRules) {
            if (r.matches(toolName, argsJson)) {
                return Decision.ASK;
            }
        }
        return Decision.ALLOW;
    }

    /** 规则总数，用于启动日志显示。 */
    public int totalRules() {
        return denyRules.size() + askRules.size();
    }
}
