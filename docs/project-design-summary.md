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

## 5. 有意简化的部分

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

## 6. 建议的讲解顺序

如果用于 15-30 分钟的小型分享，可以按下面顺序展开：

1. 项目定位：为什么要做一个零框架 Agent
2. 整体架构：启动链路和运行闭环
3. ReAct Loop：Agent 为什么是循环系统
4. Function Calling：工具调用如何落地
5. 上下文工程：静态上下文、动态状态、压缩
6. 记忆机制：`AGENTS.md`、`TodoList`、Skill
7. 边界与稳定性：权限、重试、日志
8. 有意简化的部分：哪些没做，为什么没做

## 7. 参考资料

- ReAct：<https://arxiv.org/abs/2210.03629>
- OpenAI Function Calling Guide：<https://developers.openai.com/api/docs/guides/function-calling>
- OpenAI Prompt Caching Guide：<https://developers.openai.com/api/docs/guides/prompt-caching>
- OpenAI Compaction Guide：<https://developers.openai.com/api/docs/guides/compaction>
- OpenAI Codex AGENTS.md Guide：<https://developers.openai.com/codex/guides/agents-md>
- Anthropic Building Effective AI Agents：<https://www.anthropic.com/engineering/building-effective-agents>

仓库内可配合阅读的文档：

- `docs/ai-agent-sharing.md`
- `docs/prompt-cache-aware-design.md`
- `docs/subagent-primer.md`
- `docs/2026-coding-agent-gap-analysis.md`
