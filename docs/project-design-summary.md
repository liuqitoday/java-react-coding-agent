# java-react-coding-agent：项目设计与实现概览

> 截至 2026-04-28  
> 用途：投屏分享 / 项目介绍 / Agent 原理入门

## 1. 项目定位

这是一个面向学习的 Coding Agent 项目。目标不是做生产级产品，而是用尽量少的代码，把 Agent 的核心机制完整走通。

技术选型：

- Java 17
- JDK `HttpClient`
- Gson
- OpenAI 兼容 `chat/completions` API

当前规模：

- `34` 个 Java 文件
- `3200+` 行 Java 代码
- 仅 `Gson` 一个第三方依赖

可以把这个项目概括为：

```text
LLM + Tools + Memory + Loop + Guardrails
```

## 2. 整体架构

启动阶段：

```text
Main
 ├─ AgentConfig
 ├─ LLMLogger
 ├─ LLMClient
 ├─ Compactor
 ├─ ToolRegistry
 ├─ AgentsFileLoader -> ProjectContext
 ├─ SkillRegistry -> SkillSessionState
 ├─ TodoList
 ├─ StateReminder
 ├─ PermissionPolicy -> PermissionGate
 ├─ SystemPromptBuilder
 ├─ ConversationHistory
 └─ ReActLoop
```

运行阶段：

```text
用户输入
  -> 写入 ConversationHistory
  -> 组装 messages + tools
  -> 调用 LLM
  -> 解析 assistant / tool_calls
  -> 执行工具
  -> 写回 tool result
  -> 再次调用 LLM
  -> 直到输出最终答案
```

## 3. 核心设计与实现

| 主题 | 代码位置 | 设计与实现 |
|---|---|---|
| ReAct Loop | `core/ReActLoop.java` | 采用“推理 -> 行动 -> 观察”的循环模式；当模型返回 `tool_calls` 时执行工具，没有工具调用时输出最终答案。 |
| Function Calling | `tools/Tool.java` `tools/ToolDefinition.java` `tools/ToolRegistry.java` | 每个工具统一定义名称、描述、参数 schema 和执行逻辑，再转换成 OpenAI function schema。模型负责决策，宿主程序负责执行。 |
| 基础工具集 | `tools/*` | 内置 `read_file`、`write_file`、`edit_file`、`list_files`、`execute_command`、`activate_skill`、`todo_write`。 |
| 工具边界 | `ReadFileTool` `ListFilesTool` `EditFileTool` `ExecuteCommandTool` | 大文件截断、目录递归限深、文本替换唯一匹配、命令超时，这些约束直接决定 Agent 的行为边界。 |
| 静态上下文 | `core/SystemPromptBuilder.java` | system prompt 只放 session 内稳定的内容：基础提示词、`AGENTS.md`、skill 目录摘要。 |
| 动态上下文 | `core/StateReminder.java` `core/ConversationHistory.java` | todo 和已激活 skill 作为临时消息追加到每轮请求末尾，不写入持久历史。 |
| 项目级记忆 | `memory/AgentsFileLoader.java` `memory/ProjectContext.java` | 支持 `AGENTS.md`；从 git root 扫描到当前目录，按根到叶拼接，总大小限制 `32KB`。 |
| 工作记忆 | `todo/TodoList.java` `tools/TodoWriteTool.java` | 用外部 TodoList 记录当前计划；`status` 限定为 `pending / in_progress / completed`，且最多一个 `in_progress`。 |
| 渐进披露 | `skills/SkillRegistry.java` `skills/SkillLoader.java` `tools/ActivateSkillTool.java` | 启动时只读取 skill frontmatter；只有激活时才加载正文和资源摘要。 |
| 上下文压缩 | `core/ConversationHistory.java` `core/Compactor.java` | 超过阈值后压缩较早 turn，保留最近 `3` 轮原始对话，用滚动摘要替换旧历史。 |
| 权限控制 | `permission/PermissionPolicy.java` `permission/PermissionGate.java` | 三态模型：`ALLOW / ASK / DENY`。基于工具名和参数子串进行匹配。 |
| 稳定性 | `llm/LLMClient.java` | LLM API 支持重试，对 `408`、`429`、`5xx` 和 `IOException` 做指数退避。 |
| 可观察性 | `llm/LLMLogger.java` `render/ConsoleRenderer.java` | 记录完整请求/响应日志，并把 Thought、Action、Observation、Answer 区分输出。 |

## 4. 上下文工程

项目把上下文分成三层：

| 层 | 内容 | 是否持久化 | 放置位置 |
|---|---|---|---|
| 静态上下文 | base prompt、`AGENTS.md`、skill 目录摘要 | 是 | `system` message |
| 会话历史 | `user / assistant / tool` 消息 | 是 | `ConversationHistory` |
| 动态状态 | todo、已激活 skill | 否 | 每轮请求末尾的临时消息 |

当前实现遵循一个简单原则：

> 静态内容前置，动态内容后置。

这样做的目的：

- 保持 system prompt 稳定
- 提高 Prompt Cache 的前缀命中率
- 避免过期状态反复累积到历史消息中

## 5. 实践经验

### 5.1 Tool 也是上下文

工具定义会进入模型输入，因此工具不是“零成本能力扩展”。

- OpenAI 明确说明函数定义会占用上下文；工具很多时，建议减少预加载数量，或使用 `tool search` 延迟加载。
- Google Vertex AI 对单次请求的 function declarations 设有 `128` 个上限，并建议把当前活跃工具集尽量控制在 `10-20` 个以内。
- Azure OpenAI 还对单个 tool/function description 设置了 `1024` 字符限制。

更稳妥的做法是区分“总工具库”和“当前活跃工具集”：高频工具常驻，长尾工具按需检索或路由后再暴露给模型。

### 5.2 上下文窗口不仅是容量限制，也是注意力预算

长上下文不等于高质量上下文。  
《Lost in the Middle》表明：当关键信息埋在长上下文中部时，模型效果会明显下降。

常见优化方向：

- 大结果优先外置，返回摘要、引用或路径，而不是回灌全文。
- 查询型知识优先“检索后注入”，而不是“预先全量注入”。
- 已完成阶段优先摘要，避免原始细节长期滞留。
- 能隔离到子 Agent 的探索过程，尽量不要全部堆回主上下文。

### 5.3 `AGENTS.md` 也适合做渐进式加载

`AGENTS.md` 不适合无限膨胀，更适合做“入口文件 + 索引文件”。

- 根目录 `AGENTS.md` 放全局约定、项目地图、常用命令、文档入口。
- 子目录用嵌套 `AGENTS.md` 或 `AGENTS.override.md` 放局部规则。
- 细节知识拆到独立文档，再由索引引导按需读取。

这样更符合官方推荐的分层方式，也能避免单一 `AGENTS.md` 过长后被截断。

### 5.4 Tool 描述应写“何时调用”，而不是只写“它能做什么”

工具描述的主要作用是帮助模型做路由决策，而不是给人读说明书。

比较有效的描述通常至少包含四类信息：

- 做什么
- 什么时候用
- 什么时候不要用
- 与相似工具的边界是什么

例如：

```text
较弱写法：
execute_command: Run a shell command.

较强写法：
execute_command: Use this tool when file editing tools are insufficient and you need
to run build, test, search, git status, or other short-lived shell commands that
produce observable output. Prefer read_file, list_files, and edit_file for direct
file inspection or modification. Do not use it for destructive commands unless the
user has explicitly confirmed the action.
```

描述越像“调用策略”，模型越容易稳定选对工具。

### 5.5 记忆系统的核心不是“有没有记忆”，而是“怎么存、何时取”

LLM 本身是无状态的。  
对 Agent 来说，记忆系统本质上是“外部存储 + 加载策略 + 更新策略”。

需要回答的通常是三件事：

- 怎么存：历史、摘要、文件、数据库、向量库或专门的 memory 结构
- 什么时候取：启动时加载、命中条件后加载、检索后加载，还是阶段性临时加载
- 怎么更新：追加、覆盖、摘要压缩，还是先写回 durable memory 再压缩上下文

`OpenClaw` 是一个很典型的例子：它把 memory 放在文件系统中，用 `MEMORY.md` 和 daily logs 分层存储；深层历史通过 `memory_search` 检索，而不是把全部记忆长期注入上下文。

### 5.6 Trace 与日志不是附属能力，而是 Agent 的基础设施

没有 trace 和日志，Agent 在排障时几乎就是黑盒。

建议默认记录结构化 trace：

- `trace_id / conversation_id`
- workflow / agent / model / 关键参数
- tool 调用、检索、memory load、guardrail、retry、timeout、handoff、compaction 等关键事件
- token、耗时、缓存命中、错误类型等运行指标

完整 messages、tool 输出全文、检索全文更适合作为 opt-in 的 payload 日志：

- 开发和测试环境可以完整记录，优先保证可复现性
- 生产环境更适合按需、采样、脱敏或截断记录

日志的目标不只是保存结果，而是回答三个问题：模型看到了什么，为什么做出这个决策，问题发生在哪一层。

### 5.7 先做 workflow，再做 autonomous agent

不是所有问题都需要开放式 Agent。  
Anthropic 在 Building Effective AI Agents 里把两类系统区分得很清楚：

- workflow：LLM 和工具按预定义代码路径编排
- agent：LLM 动态决定过程和工具使用

官方建议也很明确：先找最简单可行的方案，只在确实需要时再增加复杂度。

- 边界明确、步骤固定的任务，更适合 workflow
- 路径不可预测、需要持续决策的任务，才更适合 agent

优先把问题做成可预测的 workflow；只有当固定流程不够用时，再引入更强自治的 agent。

### 5.8 Tool 设计要“防错”，而不是简单映射底层 API

面向 Agent 的 tool，不应只是后端 API 的一层薄包装。  
Anthropic 在 Writing effective tools for agents 里明确提到：常见错误是把现有软件功能或 API endpoint 原样包装成工具，而不考虑这些工具是否真的适合 Agent 使用。

更合适的目标是：

- 减少模型自己拼装中间步骤的负担
- 减少把大量低价值结果推回上下文
- 让正确用法更自然，让错误用法更困难

通常更适合 Agent 的不是底层 API 包装，而是更高层、更有边界的能力单元，例如 `search_logs` 优于 `read_all_logs`，`get_customer_context` 优于一组零散查询工具。

### 5.9 模型与能力应分层路由，而不是所有任务都走同一套最强配置

在 Agent 系统里，“用同一个最强模型处理所有步骤”通常不是最优解。  
更常见的做法是按任务类型、风险级别和成本要求做分层路由：

- 搜索、探索、归纳类任务用更快、更便宜的模型
- 规划、决策、复杂生成类任务用更强的模型
- 高风险步骤叠加更严格的权限或审批机制

Claude Code 的内置 `Explore` subagent 就是一个典型例子：代码探索类子任务可以交给只读、轻量、低成本的代理处理，而不必占用主线程的最强模型。

不要把所有任务都交给同一个最强模型；更稳妥的方式是按任务类型分配不同模型、工具和权限。

### 5.10 Prompt 设计也要考虑 cache-aware layering

Prompt 不只是“写什么”，还包括“按什么顺序放进去”。  
Prompt Caching 的命中依赖稳定前缀，因此变化频率低、跨轮次基本不变的内容应尽量前置；高频变化内容应尽量后置，或做成临时注入。

比较稳妥的分层方式是：

- 最前面放稳定内容：基础 system prompt、项目长期规则、稳定工具定义
- 中间放会话历史：用户输入、assistant、tool result
- 最后放动态内容：todo、激活状态、当前回合的临时提醒、按需加载片段

这样做通常能带来更高的 cache 命中率、更少的重复计算，以及更清晰的上下文分层。

在 prompt 设计里，稳定信息应尽量前置，动态信息应尽量后置，并优先采用临时注入而不是永久写回历史。

## 6. 有意简化的部分

这个项目明确不是生产方案，因此有意省略了很多工程化能力：

- 流式输出
- Web 浏览与搜索
- MCP 客户端
- Subagent
- 会话恢复
- 并行工具调度
- 精确 tokenizer
- OS 级沙箱
- 分层压缩树

这些取舍的目的很直接：保留主干机制，降低阅读和讲解成本。

## 7. 建议的讲解顺序

如果用于 15-30 分钟的小型分享，可以按下面顺序展开：

1. 项目定位：为什么要做一个零框架 Agent
2. 整体架构：启动链路和运行闭环
3. ReAct Loop：Agent 为什么是循环系统
4. Function Calling：工具调用如何落地
5. 上下文工程：静态上下文、动态状态、压缩
6. 记忆机制：`AGENTS.md`、`TodoList`、Skill
7. 边界与稳定性：权限、重试、日志
8. 有意简化的部分：哪些没做，为什么没做

## 8. 参考资料

- ReAct：<https://arxiv.org/abs/2210.03629>
- OpenAI Function Calling Guide：<https://developers.openai.com/api/docs/guides/function-calling>
- OpenAI Prompt Caching Guide：<https://developers.openai.com/api/docs/guides/prompt-caching>
- OpenAI Compaction Guide：<https://developers.openai.com/api/docs/guides/compaction>
- OpenAI Codex AGENTS.md Guide：<https://developers.openai.com/codex/guides/agents-md>
- OpenAI Trace Grading：<https://developers.openai.com/api/docs/guides/trace-grading>
- OpenAI File Search：<https://platform.openai.com/docs/guides/tools-file-search/>
- OpenAI Retrieval：<https://platform.openai.com/docs/guides/retrieval>
- Google Vertex AI Function Calling：<https://docs.cloud.google.com/vertex-ai/generative-ai/docs/multimodal/function-calling>
- Azure OpenAI Function Calling：<https://learn.microsoft.com/en-us/azure/ai-services/openai/how-to/function-calling>
- Anthropic Building Effective AI Agents：<https://www.anthropic.com/engineering/building-effective-agents>
- Anthropic Writing Effective Tools for Agents：<https://www.anthropic.com/engineering/writing-tools-for-agents>
- Anthropic Tool Use：<https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/implement-tool-use>
- Anthropic Multi-Agent Research System：<https://www.anthropic.com/engineering/built-multi-agent-research-system>
- Claude Code Subagents：<https://code.claude.com/docs/en/sub-agents>
- AGENTS.md：<https://agents.md/>
- OpenClaw Context：<https://docs.openclaw.ai/concepts/context>
- OpenClaw Memory：<https://openclawlab.com/en/docs/concepts/memory/>
- OpenTelemetry GenAI Agent Spans：<https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-agent-spans/>
- Lost in the Middle：<https://direct.mit.edu/tacl/article/doi/10.1162/tacl_a_00638/119630/Lost-in-the-Middle-How-Language-Models-Use-Long>
- Massive Tool Retrieval：<https://arxiv.org/abs/2410.03212>

仓库内可配合阅读的文档：

- `docs/ai-agent-sharing.md`
- `docs/prompt-cache-aware-design.md`
- `docs/subagent-primer.md`
- `docs/2026-coding-agent-gap-analysis.md`
