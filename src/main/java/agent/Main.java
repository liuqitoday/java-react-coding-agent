package agent;

import agent.config.AgentConfig;
import agent.core.ConversationHistory;
import agent.core.ReActLoop;
import agent.core.SystemPrompt;
import agent.core.SystemPromptBuilder;
import agent.llm.LLMClient;
import agent.llm.LLMLogger;
import agent.memory.AgentsFileLoader;
import agent.memory.ProjectContext;
import agent.render.ConsoleRenderer;
import agent.skills.SkillRegistry;
import agent.skills.SkillSessionState;
import agent.tools.ActivateSkillTool;
import agent.tools.ToolRegistry;

import java.util.Scanner;

/**
 * 程序入口：初始化所有组件，启动 REPL 交互循环。
 *
 * 组装流程：
 * AgentConfig → LLMLogger → LLMClient → ToolRegistry
 *            → AgentsFileLoader → ProjectContext
 *            → SkillRegistry → SkillSessionState → SystemPromptBuilder
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

        // 加载项目上下文（AGENTS.md）
        AgentsFileLoader agentsLoader = new AgentsFileLoader();
        ProjectContext projectContext = agentsLoader.load();
        if (!projectContext.isEmpty()) {
            System.out.println("项目上下文: 加载了 " + projectContext.loadedFiles().size()
                    + " 个 AGENTS.md 文件");
            if (projectContext.truncated()) {
                System.out.println("  警告：项目上下文超过 32KB 限制，已截断");
            }
        }

        // 初始化 Skill 子系统
        SkillRegistry skillRegistry = new SkillRegistry();
        skillRegistry.scan();
        SkillSessionState sessionState = new SkillSessionState();

        if (!skillRegistry.isEmpty()) {
            toolRegistry.register(new ActivateSkillTool(skillRegistry, sessionState));
            System.out.println("Skills: 发现 " + skillRegistry.size() + " 个可用 skill");
        }

        // 创建 SystemPromptBuilder（统一管理 base prompt + 项目上下文 + skills）
        SystemPromptBuilder promptBuilder = new SystemPromptBuilder(
                SystemPrompt.BASE, projectContext, skillRegistry, sessionState);

        // 创建对话历史和 ReAct 循环
        ConversationHistory history = new ConversationHistory(promptBuilder.build());
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
