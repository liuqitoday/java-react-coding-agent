package agent;

import agent.skills.SkillPromptBuilder;
import agent.skills.SkillRegistry;
import agent.skills.SkillSessionState;
import agent.tools.ActivateSkillTool;

import java.util.Scanner;

/**
 * 程序入口：初始化所有组件，启动 REPL 交互循环。
 *
 * 组装流程：
 * AgentConfig → LLMLogger → LLMClient → ToolRegistry
 *            → SkillRegistry → SkillSessionState → SkillPromptBuilder
 *            → ConsoleRenderer → ReActLoop
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

        // 初始化核心组件
        LLMLogger logger = new LLMLogger();
        LLMClient llmClient = new LLMClient(config, logger);
        ToolRegistry toolRegistry = new ToolRegistry();
        ConsoleRenderer renderer = new ConsoleRenderer();

        // 初始化 Skill 子系统
        SkillRegistry skillRegistry = new SkillRegistry();
        skillRegistry.scan();
        SkillSessionState sessionState = new SkillSessionState();

        SkillPromptBuilder promptBuilder = null;
        if (!skillRegistry.isEmpty()) {
            toolRegistry.register(new ActivateSkillTool(skillRegistry, sessionState));
            promptBuilder = new SkillPromptBuilder(SystemPrompt.BASE, skillRegistry, sessionState);
            System.out.println("Skills: 发现 " + skillRegistry.size() + " 个可用 skill");
        }

        // 确定初始 system prompt
        String initialSystemPrompt = (promptBuilder != null)
                ? promptBuilder.build()
                : SystemPrompt.BASE;

        // 创建对话历史和 ReAct 循环
        ConversationHistory history = new ConversationHistory(initialSystemPrompt);
        ReActLoop reactLoop = new ReActLoop(
                llmClient, toolRegistry, renderer, config.maxIterations(), promptBuilder);

        System.out.println("模型: " + config.model());
        System.out.println("日志: " + logger.getLogFile());
        System.out.println("输入你的需求（输入 exit 退出）：");
        System.out.println();

        // REPL 主循环
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

            history.addUserMessage(input);
            reactLoop.run(history);
            System.out.println();
        }

        scanner.close();
    }
}
