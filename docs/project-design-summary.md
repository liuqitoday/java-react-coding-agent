# java-react-coding-agent：设计与实现总结

> 截至 2026-04-28 的仓库快照  
> 文档目标：给第一次接触本项目的人一个“能看懂、能顺着源码学 Agent 原理”的整体导览

## 1. 这个项目是什么

这是一个**以学习 AI Agent 原理为目的**的 Java 项目。它刻意不依赖 LangChain、Spring AI、Semantic Kernel 一类框架，而是用最直接的方式把一个 Coding Agent 的关键骨架写出来：

- Java 17
- JDK `HttpClient`
- Gson
- OpenAI 兼容 `chat/completions` API

截至 `2026-04-28`，主实现大约是：

- `34` 个 Java 文件
- `3200+` 行 Java 代码
- 只有 `Gson` 一个第三方依赖

这类项目最适合做两件事：

1. 搞清楚 Agent 到底是怎么从“聊天”变成“会行动的系统”的。
2. 搞清楚主流 Agent 产品背后的核心原理，而不是先陷进框架抽象里。

这也和 Anthropic 在 *Building Effective AI Agents* 里的建议一致：先用最简单、最可调试的方式构建，再在必要时增加复杂度。

## 2. 用一句话理解这个项目

可以把它理解成下面这个公式：

```text
Coding Agent = LLM + Tools + Memory + Loop + Guardrails
```

在这个仓库里分别对应：

| 能力 | 代码位置 | 作用 |
|---|---|---|
| `LLM` | `agent/llm/LLMClient.java` | 调用模型 |
| `Tools` | `agent/tools/*` | 读文件、改文件、执行命令、激活 skill、写 todo |
| `Memory` | `ConversationHistory`、`TodoList`、`SkillSessionState`、`AGENTS.md` | 保存会话、项目规则和工作记忆 |
| `Loop` | `agent/core/ReActLoop.java` | 负责 Thought -> Action -> Observation 的循环 |
| `Guardrails` | `agent/permission/*`、重试、日志、最大迭代限制 | 防止失控、提升可观察性 |

## 3. 总体架构

启动时的大致组装链路如下：

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

真正开始工作之后，运行时循环是这样的：

```text
用户输入
  -> 写入 ConversationHistory
  -> ReActLoop 组装 messages + tools
  -> LLM 返回 assistant 内容 / tool_calls
  -> ToolRegistry 执行工具
  -> tool result 写回 history
  -> 再次请求 LLM
  -> 直到没有 tool_calls，输出最终答案
```

这就是 ReAct 的最小闭环。

## 4. 核心功能是怎么设计和实现的

### 4.1 ReAct 循环：Agent 的核心不是“回答”，而是“循环”

对应代码：

- `agent/core/ReActLoop.java`
- `agent/core/ConversationHistory.java`
- `agent/llm/LLMClient.java`

项目采用的是非常标准的 ReAct 思路：模型不是一次性给最终答案，而是在“推理”和“行动”之间交替前进。ReAct 论文把这种模式概括为“交错地产生 reasoning traces 和 actions”，也就是一边想、一边做、一边根据环境反馈调整下一步。

这个项目里的 `ReActLoop` 做的事情很直白：

1. 把历史消息和工具定义发给模型。
2. 如果模型返回 `tool_calls`，就执行工具。
3. 把工具结果作为 `tool` 消息写回历史。
4. 再次调用模型。
5. 如果模型不再请求工具，而是直接输出文本，就认为任务结束。

这部分很值得学习，因为它几乎就是 OpenAI Function Calling 官方流程的本地实现版。

### 4.2 Function Calling：工具不是“提示词约定”，而是协议

对应代码：

- `agent/tools/Tool.java`
- `agent/tools/ToolDefinition.java`
- `agent/tools/ToolRegistry.java`

项目把每个工具都抽象成统一接口：

- `name()`
- `description()`
- `parameterSchema()`
- `execute(args)`

然后由 `ToolDefinition` 自动转成 OpenAI function schema，放到请求的 `tools` 数组里。这样模型看到的不是一段“你可以调用 read_file”之类的自然语言，而是结构化的工具协议。

这部分的学习价值很高，因为它回答了一个初学者最常见的问题：

> Agent 到底怎么“调用工具”？

答案不是“模型真的执行了代码”，而是：

- 模型返回一个结构化的工具调用意图
- 宿主程序解析它
- 宿主程序执行本地代码
- 再把结果发回模型

也就是说，**LLM 负责决策，宿主程序负责执行**。

### 4.3 工具系统：把“感知环境”和“改变环境”拆开

内置工具可以按 Agent 原理分成两类：

| 类型 | 工具 | 学习意义 |
|---|---|---|
| 感知环境 | `read_file`、`list_files` | 让模型先看清环境再决策 |
| 改变环境 | `write_file`、`edit_file`、`execute_command` | 让模型真正对外部世界产生影响 |
| 扩展能力 | `activate_skill` | 让模型按需加载额外知识 |
| 外部工作记忆 | `todo_write` | 让模型维护计划和进度 |

这里有几个很好的设计点：

- `read_file` 有 10000 字符截断，避免大文件直接把上下文塞爆。
- `list_files` 的递归遍历有最大深度和忽略目录，避免把整个工程树一口气读进去。
- `edit_file` 要求 `old_string` 精确匹配，并且默认必须唯一出现，这是一个非常典型的“学习型安全设计”。
- `execute_command` 有 30 秒超时，不让模型把宿主进程无限阻塞住。

这些都不是生产级能力，但非常适合帮助学习者理解：**工具设计会直接影响 Agent 的行为边界**。

### 4.4 上下文工程：这是项目里最值得学习的一部分

对应代码：

- `agent/core/SystemPromptBuilder.java`
- `agent/core/StateReminder.java`
- `agent/core/ConversationHistory.java`
- `agent/memory/AgentsFileLoader.java`
- `agent/core/Compactor.java`

如果只看“能跑”，Agent 很容易写出来；但如果要让它在多轮任务里不迅速变笨，上下文工程才是真正的关键。

这个项目把上下文分成了三层：

| 层 | 内容 | 是否持久化 | 放置位置 |
|---|---|---|---|
| 静态系统上下文 | 基础 system prompt、AGENTS.md、skill 目录摘要 | 是 | `system` message |
| 会话历史 | user / assistant / tool 消息 | 是 | `ConversationHistory.messages` |
| 动态状态 | 当前 todo、已激活 skill | 否 | 每轮请求末尾的临时 `developer` message |

这里最重要的设计原则是：

> 静态内容放前面，动态内容放后面。

这样做的原因很直接。OpenAI 的 Prompt Caching 文档明确写到：缓存只对**完全相同的前缀**生效，静态内容应该放在开头，变化内容应该放在末尾。这个项目正是按这个原则实现的：

- `SystemPromptBuilder` 只组装 session 内不变的内容。
- `StateReminder` 每轮动态生成 reminder。
- `ConversationHistory.toJsonArray(reminder)` 在发送请求时，把 reminder 作为临时消息挂到最后。
- 这条临时消息不写回持久历史，因此不会反复累积过期状态。

这是一个非常适合教学的设计，因为它比“把所有状态都塞进 system prompt”更能体现现代 Agent 的上下文管理思路。

### 4.5 AGENTS.md：项目级长期记忆

对应代码：

- `agent/memory/AgentsFileLoader.java`
- `agent/memory/ProjectContext.java`

这个项目支持读取 `AGENTS.md`，让 Agent 在启动时就知道项目规范、工作方式和局部目录约束。

实现方式也很贴近公开规范：

- 从 git root 一直扫描到当前工作目录
- 每级目录优先 `AGENTS.override.md`，其次 `AGENTS.md`
- 按从根到叶的顺序拼接
- 总大小限制为 `32KB`

这部分很适合用来理解“项目记忆”和“会话记忆”的区别：

- `AGENTS.md` 是跨会话、相对稳定的长期上下文
- `ConversationHistory` 是当前会话的短期上下文

### 4.6 TodoList：最小但很有代表性的外部工作记忆

对应代码：

- `agent/tools/TodoWriteTool.java`
- `agent/todo/TodoList.java`
- `agent/todo/TodoItem.java`
- `agent/core/StateReminder.java`

`todo_write` 是这个项目里一个非常有学习价值的设计。它的作用不是“记个列表”这么简单，而是把模型的计划外置出来，变成宿主程序可见、可约束、可重复注入的状态。

这个实现有三个关键点：

- 采用“全量替换”而不是增删改单个 todo，降低模型调用难度。
- 强制 `status` 只能是 `pending / in_progress / completed`。
- 同一时间最多只有一个 `in_progress`，逼着模型按顺序推进任务。

这就是一个很典型的 Agent 学习点：

> 你不一定非得让模型在脑子里记住计划，也可以给它一个外部工作记忆结构。

### 4.7 Skill：按需加载，而不是一开始全塞进去

对应代码：

- `agent/skills/SkillRegistry.java`
- `agent/skills/SkillLoader.java`
- `agent/skills/FrontmatterParser.java`
- `agent/skills/SkillSessionState.java`
- `agent/tools/ActivateSkillTool.java`

Skill 子系统体现的是一个非常重要的上下文工程思想：**progressive disclosure（渐进披露）**。

项目的做法是：

- 启动时只扫描 `.agents/skills/*/SKILL.md` 的 frontmatter
- 先拿到 `name` 和 `description`，构成一个轻量目录
- 只有当模型判断某个 skill 真有用时，才调用 `activate_skill`
- 激活时再加载 SKILL 正文和目录里的资源摘要

这类设计的价值在于：

- 不把所有说明文档一次性塞进上下文
- 让模型先做路由，再做加载
- 让上下文成本随着真实任务逐步增加

这也是现代 Agent 很常见的一类思路。

### 4.8 上下文长度管理：当前仓库已经有一个“最简单可讲清楚”的实现

对应代码：

- `agent/core/ConversationHistory.java`
- `agent/core/Compactor.java`
- `agent/core/ReActLoop.java`
- `agent/config/AgentConfig.java`

当前实现不是复杂的生产版压缩器，而是一个很克制的教学版：

- 用字符数粗略估算 token，而不是引入 tokenizer
- 达到阈值后，只压缩较早 turn，保留最近 `3` 轮原始对话
- turn 的边界以 `user` 消息为起点，后面跟随的 `assistant / tool` 都算同一轮
- 用同一个主模型生成滚动摘要，不单独引入摘要模型
- 用一对合成的 `user + assistant` 摘要消息替换旧历史

这个设计和 OpenAI 在 Compaction 文档里描述的目标是一致的：**随着对话增长，减少上下文体积，同时保留继续工作所需的状态**。只是本项目为了学习成本更低，采用了最容易读懂的客户端压缩实现，而不是更复杂的服务端压缩协议。

这里最值得学习的点不是“摘要质量有多高”，而是：

- 为什么压缩要按 turn 进行，而不能随便截断
- 为什么要保留最近若干轮原始消息
- 为什么旧历史更适合摘要，新近历史更适合保真

### 4.9 权限系统：Agent 不是会调用工具就够了，还要有边界

对应代码：

- `agent/permission/PermissionPolicy.java`
- `agent/permission/PermissionGate.java`
- `agent/permission/Decision.java`

权限系统采用的是非常适合学习的最小模型：

- `ALLOW`
- `ASK`
- `DENY`

规则文件是 `permissions.json`，匹配方式也很简单：

- 先匹配工具名
- 再用参数 JSON 的子串包含做判断

例如：

- `execute_command:rm -rf` 可以直接拒绝
- `execute_command:` 可以统一要求审批

这套实现不是为了覆盖所有真实攻击面，而是为了把一个重要原则讲清楚：

> 工具边界最好在宿主程序里硬编码，而不是只靠 system prompt 让模型“自觉”。

### 4.10 稳定性与可观察性：重试、日志、终端渲染

对应代码：

- `agent/llm/LLMClient.java`
- `agent/llm/LLMLogger.java`
- `agent/render/ConsoleRenderer.java`

这部分虽然不是“Agent 理论”，但对学习非常重要，因为没有可观察性，就很难真正理解 Agent 在做什么。

当前仓库已经实现了：

- LLM API 重试
  - 对 `408`、`429`、`5xx` 做重试
  - 对 `IOException` 做重试
  - 指数退避，支持 `initial-delay` 和 `max-delay`
- 完整请求/响应日志
  - 每次启动新建 `logs/llm_*.log`
  - 记录请求体、响应体、重试和错误
- 终端渲染
  - 把 Thought、Action、Observation、Answer 分颜色打印

对学习者来说，这几乎相当于一个“Agent 透明观察窗”。你可以很方便地知道：

- 模型什么时候决定调用工具
- 调用了什么参数
- 工具返回了什么
- 为什么发生了重试

## 5. 这个项目为什么适合学习，而不适合直接当生产方案

它的长处和短处其实是同一件事：**它故意简单**。

项目刻意没有做很多生产级能力，例如：

- 没有流式输出
- 没有 OS 级沙箱
- 没有 Web 搜索 / 浏览器工具
- 没有 MCP 客户端
- 没有 Subagent
- 没有并行工具调度
- 没有精确 tokenizer
- 没有持久化会话恢复
- 没有复杂的摘要树或分层压缩

但正因为它没把这些东西一次性堆进来，学习者才能很清楚地看到每一层机制是怎么工作的。

如果把这个项目当成“学习型骨架”，它的价值很高；如果把它当成“生产级 Agent 成品”，那它显然还远远不够。

## 6. 推荐怎么阅读源码

最推荐的阅读顺序是：

1. `agent/Main.java`  
   先看程序是怎么把各个组件组装起来的。
2. `agent/core/ReActLoop.java`  
   看 Agent 的主循环到底怎么转。
3. `agent/llm/LLMClient.java` 和 `agent/core/ConversationHistory.java`  
   看请求是怎么发出去的、消息是怎么组织的。
4. `agent/tools/Tool.java`、`ToolRegistry.java`、`ToolDefinition.java`  
   看工具协议层。
5. `agent/tools/ReadFileTool.java`、`EditFileTool.java`、`TodoWriteTool.java`  
   看几个最有代表性的工具设计。
6. `agent/core/SystemPromptBuilder.java`、`StateReminder.java`、`Compactor.java`  
   看上下文工程。
7. `agent/memory/AgentsFileLoader.java`、`agent/skills/*`  
   看长期记忆和渐进披露。
8. `agent/permission/*`、`agent/llm/LLMLogger.java`  
   看治理和可观察性。

如果只是做一次内部分享，按上面顺序读，已经足够覆盖 Agent 最核心的一批概念。

## 7. 这个仓库和主流 Agent 原理的对应关系

| 本项目能力 | 对应原理 | 参考来源 |
|---|---|---|
| `ReActLoop` | ReAct：交错推理与行动 | ReAct 论文 |
| `ToolDefinition` + `ToolRegistry` | Function Calling / Tool Calling | OpenAI Function Calling Guide |
| `AgentsFileLoader` | 项目级指令链 | OpenAI Codex AGENTS.md Guide |
| `StateReminder` 的末尾注入 | Prompt Caching 的前缀稳定性 | OpenAI Prompt Caching Guide |
| `Compactor` | 长对话压缩 | OpenAI Compaction Guide |
| 零框架、直连 API | 从简单系统起步 | Anthropic Building Effective AI Agents |

所以这个项目虽然小，但它覆盖的并不是“玩具概念”，而是今天主流 Agent 产品反复出现的那批核心思想。

## 8. 如果要继续扩展，最值得补什么

如果你的目标仍然是“学习优先”，而不是“做产品”，最推荐的后续扩展顺序是：

1. `streaming`
2. `MCP client`
3. `subagent`
4. `session resume`
5. 更细粒度的上下文压缩

原因很简单：

- `streaming` 能补齐交互体验
- `MCP` 能帮你理解 Agent 工具生态的标准化方向
- `subagent` 能帮你理解多 Agent 与上下文隔离
- `session resume` 能帮你理解状态持久化
- 更细粒度压缩能帮你理解长任务下的成本和质量平衡

如果想继续看这些主题，仓库里已经有几篇延伸文档可读：

- `docs/prompt-cache-aware-design.md`
- `docs/subagent-primer.md`
- `docs/2026-coding-agent-gap-analysis.md`

## 9. 总结

这个项目最有价值的地方，不是“功能很多”，而是它把一个 Agent 的关键组成拆得足够清楚：

- 用 `ReActLoop` 讲清楚 Agent 为什么是循环系统
- 用 `Tool` 抽象讲清楚工具调用为什么是协议问题
- 用 `AGENTS.md`、`TodoList`、`StateReminder`、`Compactor` 讲清楚上下文工程和记忆分层
- 用 `PermissionGate`、重试、日志讲清楚 Agent 为什么需要边界、恢复能力和可观察性

如果你的目标是**学习 AI Agent 的原理、设计与实现方式**，这个仓库已经是一个很好的最小教材。

如果未来继续扩展，也建议继续保持这个项目现在最宝贵的特性：**每一项能力都用尽量少的代码，把原理说明白。**

## 10. 参考资料

### 一手资料

- ReAct 论文：<https://arxiv.org/abs/2210.03629>
- OpenAI Function Calling Guide：<https://developers.openai.com/api/docs/guides/function-calling>
- OpenAI Prompt Caching Guide：<https://developers.openai.com/api/docs/guides/prompt-caching>
- OpenAI Compaction Guide：<https://developers.openai.com/api/docs/guides/compaction>
- OpenAI Codex AGENTS.md Guide：<https://developers.openai.com/codex/guides/agents-md>
- Anthropic Building Effective AI Agents：<https://www.anthropic.com/engineering/building-effective-agents>

### 本仓库内的延伸文档

- `docs/ai-agent-sharing.md`
- `docs/prompt-cache-aware-design.md`
- `docs/subagent-primer.md`
- `docs/2026-coding-agent-gap-analysis.md`
