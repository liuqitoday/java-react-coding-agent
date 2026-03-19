package agent;

import java.util.Scanner;

/**
 * 程序入口：初始化所有组件，启动 REPL 交互循环。
 *
 * 组装流程：AgentConfig → LLMLogger → LLMClient → ToolRegistry → ConsoleRenderer → ReActLoop
 * 然后进入"读取用户输入 → 执行 ReAct 循环 → 等待下一次输入"的主循环。
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║   ReAct Coding Agent (Java 17)      ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println();

        // 加载配置
        AgentConfig config = new AgentConfig();

        if (config.apiKey().isBlank()) {
            System.err.println("错误：未配置 API Key。");
            System.err.println("请设置环境变量 OPENAI_API_KEY，或编辑 agent.properties 文件。");
            System.exit(1);
        }

        // 初始化各组件
        LLMLogger logger = new LLMLogger();
        LLMClient llmClient = new LLMClient(config, logger);
        ToolRegistry toolRegistry = new ToolRegistry();
        ConsoleRenderer renderer = new ConsoleRenderer();
        ReActLoop reactLoop = new ReActLoop(llmClient, toolRegistry, renderer, config.maxIterations());

        // 创建对话历史，注入系统提示词
        ConversationHistory history = new ConversationHistory(config.systemPrompt());

        System.out.println("模型: " + config.model());
        System.out.println("日志: " + logger.getLogFile());
        System.out.println("输入你的需求（输入 exit 退出）：");
        System.out.println();

        // REPL 主循环：读取输入 → 执行 ReAct → 输出结果
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\033[1m> \033[0m");
            if (!scanner.hasNextLine()) {
                break;
            }
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }
            if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                System.out.println("再见！");
                break;
            }

            // 将用户消息加入对话历史，然后启动 ReAct 循环
            history.addUserMessage(input);
            reactLoop.run(history);
            System.out.println();
        }

        scanner.close();
    }
}
