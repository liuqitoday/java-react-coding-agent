package agent;

import agent.config.AgentConfig;
import agent.core.ConversationHistory;
import agent.core.ReActLoop;
import agent.core.StateReminder;
import agent.core.SystemPrompt;
import agent.core.SystemPromptBuilder;
import agent.llm.LLMClient;
import agent.llm.LLMLogger;
import agent.memory.AgentsFileLoader;
import agent.memory.ProjectContext;
import agent.permission.PermissionGate;
import agent.permission.PermissionPolicy;
import agent.render.ConsoleRenderer;
import agent.skills.SkillRegistry;
import agent.skills.SkillSessionState;
import agent.todo.TodoList;
import agent.tools.ActivateSkillTool;
import agent.tools.TodoWriteTool;
import agent.tools.ToolRegistry;

import java.nio.file.Path;
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

        // 初始化 Todo 子系统（LLM 的外部工作记忆）
        TodoList todoList = new TodoList();
        toolRegistry.register(new TodoWriteTool(todoList));

        // 会话动态状态摘要：把每轮可能变化的内容（todos / 已激活 skills）
        // 作为临时 developer message 追加到请求末尾，不写入持久历史——
        // 这样保持 system message 静态，让 Prompt Cache 前缀匹配能稳定命中
        StateReminder stateReminder = new StateReminder(todoList, sessionState);

        // 加载权限策略（permissions.json 不存在则为空策略：所有工具 ALLOW）
        PermissionPolicy permissionPolicy = PermissionPolicy.load(Path.of("permissions.json"));
        // Scanner 提前创建：PermissionGate 的 ASK 交互和下面的 REPL 必须共用同一个实例
        Scanner scanner = new Scanner(System.in);
        PermissionGate permissionGate = new PermissionGate(permissionPolicy, renderer, scanner);
        if (permissionPolicy.totalRules() > 0) {
            System.out.println("权限策略: 加载了 " + permissionPolicy.totalRules() + " 条规则");
        }

        // 创建 SystemPromptBuilder（只放 Session 内静态的内容：base + 项目上下文 + skill 目录）
        SystemPromptBuilder promptBuilder = new SystemPromptBuilder(
                SystemPrompt.BASE, projectContext, skillRegistry);

        // 创建对话历史和 ReAct 循环
        ConversationHistory history = new ConversationHistory(promptBuilder.build());
        ReActLoop reactLoop = new ReActLoop(
                llmClient, toolRegistry, renderer, config.maxIterations(),
                stateReminder, permissionGate, todoList);

        System.out.println("模型: " + config.model());
        System.out.println("日志: " + logger.getLogFile());
        System.out.println("输入你的需求（输入 exit 退出）：");
        System.out.println();

        // REPL 主循环（复用上面已创建的 scanner）
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
