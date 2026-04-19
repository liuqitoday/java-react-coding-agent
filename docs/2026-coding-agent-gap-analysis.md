# 2026 Coding Agent 能力调研与学习路线

> 撰写日期：2026-04-19
> 对标对象：Claude Code、OpenAI Codex CLI、Cursor（均为 2026 Q1 最新状态）
> 目标读者：想通过本项目学习 AI Agent 原理的工程师

---

## 1. 为什么做这次调研

本项目（`java-react-coding-agent`）是一个**以学习为目的**的 ReAct Coding Agent 实现：Java 17 + Gson + JDK HttpClient，不依赖任何 Agent 框架，用最少代码打穿"用户输入 → LLM 推理 → 工具调用 → 结果反馈"的完整闭环。

但 2026 年的 Coding Agent 行业变化极快。关键节点：

- **Claude Code**（Anthropic）：2025-02 公测，至 2026-04 已开放 MCP / Subagent / Hooks / Plugins / Skills / Agent Teams 六大扩展点；4% 的公开 GitHub commit（约 13.5 万/天）由它产生。
- **OpenAI Codex CLI**：2025 下半年核心用 Rust 重写，默认模型 GPT-5.4（272K 上下文，可扩 1M）；内建 Apple Seatbelt / Landlock 内核级沙箱；SQLite 持久化 thread，支持 resume；2026-03 月活超 300 万。
- **Cursor**：2026-04 发布 Cursor 3，中心是 Composer 2 模型 + Background Agents（最多 8 个并行云端 Agent）+ 多仓库布局 + Design Mode。
- **MCP（Model Context Protocol）**：Anthropic 于 2024-11 提出，2025-12 捐赠给 Linux 基金会 Agentic AI Foundation，已成为 Agent 对外接工具的事实标准。

几个月没动的本项目，相比这几家"覆盖面"上确实显得单薄。但——**作为学习项目，我们不需要追平产品能力，只需要覆盖其背后的核心原理**。本文就是对着"原理 × 代价"做一次盘点，给出分阶段的学习路线。

本文不是产品对比评测，而是**原理地图 + 能力差距**。

---

## 2. 现代 Coding Agent 的心智模型

把 Claude Code / Codex / Cursor 的所有能力糊在一起看会很乱。推荐用下面这个**四层心智模型**理解它们：

```
┌──────────────────────────────────────────────────────────┐
│  ④ Governance Layer      权限 / 沙箱 / 审批 / 审计       │
├──────────────────────────────────────────────────────────┤
│  ③ Extension Layer       Tools / Skills / MCP / Hooks /  │
│                          Subagents / Plugins             │
├──────────────────────────────────────────────────────────┤
│  ② Context Layer         项目记忆 / 会话压缩 /            │
│                          工作记忆 / 长期记忆              │
├──────────────────────────────────────────────────────────┤
│  ① Core Loop             ReAct / Multi-turn Tool Use     │
└──────────────────────────────────────────────────────────┘
```

每一层承担不同职责。学懂这四层的原理，就等于学懂了主流 Coding Agent 的骨架。

### 2.1 四层能力矩阵

| 层 | 能力 | 本项目 | Claude Code | Codex | Cursor |
|---|---|:-:|:-:|:-:|:-:|
| ① Core | ReAct 循环 | ✓ | ✓ | ✓ | ✓ |
| ① Core | Function Calling | ✓ | ✓ | ✓ | ✓ |
| ① Core | 流式输出 + 中断 | ✗ | ✓ | ✓ | ✓ |
| ② Context | 项目记忆（AGENTS.md / CLAUDE.md） | ✓ | ✓ | ✓ | △ |
| ② Context | Auto-Compact 自动压缩 | ✗ | ✓ | ✓ | ✓ |
| ② Context | 工作记忆（Todo/Plan） | ✗ | ✓ | ✓ | ✓ |
| ② Context | Session Resume | ✗ | △ | ✓ | ✓ |
| ② Context | Prompt Caching | ✗ | ✓ | ✓ | ✓ |
| ③ Extension | Skills（渐进披露） | ✓ | ✓ | ✓ | ✗ |
| ③ Extension | Subagent | ✗ | ✓ | ✓ | ✓ |
| ③ Extension | MCP Client | ✗ | ✓ | ✓ | ✓ |
| ③ Extension | Hooks | ✗ | ✓ | ✓ | ✗ |
| ③ Extension | Web 工具 | ✗ | ✓ | ✓ | ✓ |
| ③ Extension | Edit/Grep/Glob 等专用工具 | ✗ | ✓ | ✓ | ✓ |
| ④ Governance | 权限模式（allow/deny/ask） | ✗ | ✓ | ✓ | ✓ |
| ④ Governance | OS 级沙箱 | ✗ | ✓ | ✓ | △ |
| ④ Governance | Plan Mode 只读规划 | ✗ | ✓ | △ | ✓ |
| ④ Governance | 审计日志 | △ | ✓ | ✓ | ✓ |

> △ 表示"部分实现"或"以不同形式存在"。本项目的 `LLMLogger` 只是请求/响应日志，不算完整审计。

---

## 3. 本项目已覆盖的原理

内部分享时**先肯定已学到的**，再讲还缺什么。以下是项目已经打穿、可以直接讲解的原理，每一项都附代码定位：

| 原理 | 代码定位 | 一句话概括 |
|---|---|---|
| ReAct 循环 | `core/ReActLoop.java:47-129` | Thought→Action→Observation 多轮直到无 tool_calls |
| OpenAI Function Calling Schema 生成 | `tools/ToolDefinition.java` / `tools/ToolRegistry.java` | Java Tool 接口 → JSON Schema 自动转换 |
| 四角色消息管理 | `core/ConversationHistory.java` | system / user / assistant / tool；system 动态可变 |
| 动态 System Prompt 组装 | `core/SystemPromptBuilder.java` | 每轮调用前重新拼接，反映最新状态 |
| 项目级记忆（AGENTS.md 开放规范） | `memory/AgentsFileLoader.java` | 从 git root → cwd 逐级读取，32KB 上限 |
| Skill 渐进披露（Progressive Disclosure） | `skills/SkillRegistry.java` + `tools/ActivateSkillTool.java` | 启动只读 frontmatter（~100 token），激活时才读正文 |
| 会话级 Skill 状态 | `skills/SkillSessionState.java` | 避免重复激活 |
| API 请求/响应审计日志 | `llm/LLMLogger.java` | 每次启动新开一份 pretty JSON 日志 |

**这一块本身就是一次完整的内部分享选题**："用 14 个 Java 文件演示 Function Calling 与 ReAct 的每一个细节"。内容已经够讲 40 分钟。

---

## 4. 缺失能力的原理深解

每一项统一用五段式讲解：**问题 → 主流方案 → 原理 → 最小落地 → 延伸**。

按学习优先级分为 P0 / P1 / P2 三层。

### P0 — 最核心、最能强化原理理解的四项

#### 4.1 上下文压缩（Context Compaction / Auto-Compact）

**问题**：Context Window 是 Agent 的"RAM"。一个 3 小时的 Coding Session 轻松烧掉 300K token，但 Sonnet 4.6 只有 200K 额度。研究显示，SWE-agent 的 context 增长速度远快于任务质量提升——token 涨了性能却不涨，属于典型"越跑越贵，越贵越傻"。

**主流方案**：
- **Claude Code**：2026 年新增 *context editing*，自动清除陈旧 tool 输出；默认在 150K token 触发 summary + 压缩块，100 轮测评可减少 84% token 消耗。
- **Codex**：`model_context_window` + `model_auto_compact_token_limit` 可配置；默认 272K，必要时 1M。
- **Spring AI Session**（通用范式）：以"turn"为原子单位（一条 user + 所有后续 assistant/tool 消息直到下一条 user），保证不会在 tool_call↔tool_result 中间切断。

**原理**：
1. **Token 估算**：粗糙估算即可，英文约 4 字符/token，中文约 2 字符/token。
2. **Turn-safe boundary**：绝不能把 `assistant(tool_calls)` 和后续 `role=tool` 消息分开——API 会直接报错。
3. **两种策略搭配**：
   - **Clipping**：针对单条超长结果（大段文件、巨量搜索命中）就地截断，成本为零。
   - **Summarization**：把前 N-K 轮交给 LLM 压成一条 synthetic `user` + `assistant` 消息。高层事实（任务目标、关键文件、决策理由）会被保留，细节（某行某列）会丢失。
4. **递归摘要**：下次再触发时，在"已有摘要 + 新轮次"上再压一次，形成滚动式压缩历史。

**本项目最小落地**：
- 在 `ConversationHistory` 上新增 `estimateTokens()` 和 `needsCompaction(threshold)`。
- 新建 `core/Compactor.java`：接收 `List<JsonObject> messages`，调 LLM（用更便宜的模型）产出摘要。
- `ReActLoop.run()` 每轮开头检查阈值，命中就压缩；保留最近 K 个 turn（K=3 足够）不压。
- 关键测试：验证压缩后再跑一轮，LLM 不会因看不见 tool_calls 对应的 tool 结果而报错。

**延伸阅读**：
- Anthropic [Context Engineering Cookbook](https://platform.claude.com/cookbook/tool-use-context-engineering-context-engineering-tools)
- [Compaction: The Hidden Trick…](https://practiceoverflow.substack.com/p/compaction-the-hidden-trick-that)
- [Spring AI Session API](https://spring.io/blog/2026/04/15/spring-ai-session-management/)

---

#### 4.2 权限系统（Permission Modes）

**问题**：本项目的 `ExecuteCommandTool` 直接 `/bin/sh -c` 执行任意命令，没有任何校验。一旦 LLM 被 prompt injection 诱导执行 `rm -rf ~/`，或者用户自己不小心让它"清理一下临时文件"——真实事故已经发生过（Claude Code 用户丢失整个 home 目录的 rm -rf 事件有公开记录）。

**主流方案**：
- **Claude Code**：两层设计。**权限层** = 工具名 + 参数匹配（allow / deny / ask 三态），**沙箱层** = macOS Seatbelt / Linux Landlock 做 OS-level 隔离。二者不可替代：权限防常规失误，沙箱防权限被绕过。
- **Codex**：v0.115+ 默认 Bubblewrap 沙箱，内置 Smart Approvals 子 Agent 做"这是不是危险操作"的判断。
- **Cursor**：动态生成沙箱 profile，一个任务一个 profile；对 rm/git reset 等自动降级到 ask。

**原理**：
1. **PreToolUse 拦截**：在工具真正执行前，过一个策略判断器：命中 allow → 放行；命中 deny → 直接拒绝；都没命中 → 弹给用户决定（ask）。
2. **避免"审批疲劳"**：只对真正危险的操作 ask，其他 silently allow。Anthropic 公开数据：引入沙箱后，让用户看到的 prompt 减少 84%。
3. **按参数匹配，不只是按工具名**：`execute_command("ls")` 放行，`execute_command("rm -rf /")` 拒绝。
4. **策略分层**：项目级 `.agent/permissions.json` + 用户全局 `~/.agent/permissions.json`。

**本项目最小落地**：
- 新建 `tools/ToolGate.java` 接口：`Decision check(String toolName, JsonObject args)`（Decision = ALLOW / DENY / ASK）。
- `ReActLoop` 在 `toolRegistry.execute()` 前调用 `gate.check()`；ASK 则暂停循环、让 `Main` 的 REPL 拿到用户输入再继续。
- 规则文件用 JSON：`{"allow":["read_file","list_files"],"deny":["execute_command:rm*"],"ask":["write_file","execute_command"]}`，采用简单 glob 匹配。
- **不做沙箱**。讲清楚"权限 ≠ 沙箱"这点就够了，真正沙箱化离开 JVM 体系，不适合本项目。

**延伸阅读**：
- [Claude Code Sandboxing](https://code.claude.com/docs/en/sandboxing)
- [Anthropic — making Claude Code more secure and autonomous](https://www.anthropic.com/engineering/claude-code-sandboxing)
- [Cursor — Implementing a secure sandbox](https://cursor.com/blog/agent-sandboxing)

---

#### 4.3 Subagent（任务委派与上下文隔离）

**问题**：主 Agent 干搜索/探索类工作时，工具返回的 raw 结果会把 context 占爆——比如 `grep -r` 整个仓库。越到后面模型越"看不清"——这就是 context dilution。

**主流方案**：
- **Claude Code**：`.claude/agents/*.md` 定义 Subagent（独立 system prompt、独立 tool allowlist、独立 model）。一条规则：Subagent 不能再生成 Subagent，防止递归爆炸。
- **Codex**：`[agents]` 段配置；2026-03 引入 v2，支持 `/root/agent_a` 式路径寻址和 Agent 间结构化消息。
- **Cursor**：Composer 2 架构下 Subagent 并行探索，每个可选不同模型。

**核心原理**（Anthropic 官方总结）：**"Share memory by communicating, don't communicate by sharing memory"**。
- 子 Agent 用**干净的 context** 去探索，主 Agent 永远看不到子 Agent 的 raw tool 结果，只看到"结论摘要"。
- 这其实不是新东西——操作系统里的进程隔离、Erlang 的 Actor 模型，都是同一思路。
- 子 Agent 可以用更便宜的模型（Haiku 4.5 做机械查询，Sonnet 4.6 主力，Opus 4.7 做架构决策）。

**本项目最小落地**：
- 新建 `tools/SubagentTool.java`。execute 时：
  - 内部 `new ConversationHistory(subagentSystemPrompt)` + `new ReActLoop(...)`。
  - 把用户的子任务描述作为 user message。
  - 跑完后取最后一条 assistant content 当作 observation 返回给主循环。
- 核心约束：**`SubagentTool` 自身不注册到子 Agent 的 `ToolRegistry`**，从根源上防止递归。
- 讲课点："这里有 `summary`、有隔离，就构成了 Multi-Agent 架构的雏形——分层树的节点就是这样组合起来的。"

**延伸阅读**：
- [Claude Code Subagents](https://code.claude.com/docs/en/sub-agents)
- [Anthropic Multi-agent Research System](https://www.anthropic.com/engineering/multi-agent-research-system)
- Phase Transition for Budgeted Multi-Agent Synergy（2026-01，arxiv）

---

#### 4.4 MCP（Model Context Protocol）基础

**问题**：每个 Agent 接入每个外部工具都要写一次集成——这是典型的 N×M 问题。在 MCP 之前，Claude 接 GitHub、Codex 接 GitHub、Cursor 接 GitHub，每家都写一遍。

**主流方案**：
- **MCP**：2024-11 Anthropic 发布，2025-12 捐给 Linux 基金会 Agentic AI Foundation。OpenAI/Google/Cursor 已全部接入。到 2026 Q1 已有 3000+ 现成 MCP Server。
- 相当于"USB-C for AI tools"：一次实现、所有 Agent 能用。

**原理**：
1. **三角色**：Host（用户侧应用）/ Client（Host 内的协议实现）/ Server（提供能力的进程）。
2. **三种能力**：Tools（可调用函数）/ Resources（可读数据，文件式 URI）/ Prompts（可复用的 prompt 模板）。
3. **传输**：stdio（本地子进程）或 Streamable HTTP。本地工具选 stdio，远程服务选 HTTP。
4. **协议**：基于 JSON-RPC 2.0，消息格式跟 Language Server Protocol 同源。
5. **关键动作**：`initialize`（版本协商）→ `tools/list`（发现能力）→ `tools/call`（执行）。

**本项目最小落地**（强烈建议只做 stdio + tools，不要贪）：
- 新建 `mcp/` 包：`McpClient` / `McpTransport` / `StdioTransport` / `JsonRpcCodec`。
- `McpClient` 启动时 fork 子进程、通过 stdin/stdout 交换 JSON-RPC。
- 拿到 `tools/list` 响应后，把每个 tool 包装成本项目的 `Tool` 接口实现（`McpToolAdapter`），动态注册到 `ToolRegistry`。
- 配置文件 `mcp.json`：`{"servers":{"filesystem":{"command":"npx","args":["@modelcontextprotocol/server-filesystem","/some/path"]}}}`。
- 测试：起一个官方 filesystem server，让 Agent 通过它读文件。

**讲课点**：
- "我们自己写的 `ReadFileTool` 和 MCP 的 filesystem server 其实干同样的事。区别是：后者能被所有支持 MCP 的 Agent 复用。"
- "协议跟 LSP 同源——这是把'工具发现'这件事做成工业标准的正确姿势。"

**延伸阅读**：
- [MCP 官方文档](https://modelcontextprotocol.io/docs/getting-started/intro)
- [Anthropic MCP Introduction](https://www.anthropic.com/news/model-context-protocol)
- [mcp-agent 参考实现（lastmile-ai）](https://github.com/lastmile-ai/mcp-agent)

---

### P1 — 工程化能力，让 Agent 真正能干活

这一层不涉及深刻的新原理，但是让"玩具"变成"能用"的关键。

#### 4.5 流式输出 + 中断（Streaming + ESC）

**问题**：当前 `LLMClient.chatCompletion()` 是同步阻塞的，用户看着光标干瞪眼半分钟。真实 Coding Agent 必须流式，且允许 ESC 中止。

**原理**：OpenAI `stream: true` 返回 Server-Sent Events（`data: {...}\n\n` 行分隔）；本项目可用 JDK `HttpClient.sendAsync()` + `BodyHandlers.ofLines()` 逐行读。中断靠 `CompletableFuture.cancel(true)`。

**本项目最小落地**：改造 `LLMClient` 支持 streaming 模式，`ConsoleRenderer` 加打字机效果，`Main` 的 REPL 开一个独立线程监听键盘（JDK 的 `System.in` 在原始终端比较折腾，简化为 `Ctrl+C` 处理也可）。

---

#### 4.6 Plan Mode（只读规划模式）

**问题**：直接让 LLM 动手时，它对大改动缺乏整体思路，容易越改越乱。

**原理**：一种**元模式**——在大改动前切到"只读模式"：工具 allowlist 只留 Read/Grep/Glob 等非破坏性工具，LLM 被迫先产出计划再征求用户同意，同意后才切回完整工具集。核心实现是**动态工具集 + 状态机**。

**本项目最小落地**：`ReActLoop` 增加 `Mode` 枚举（NORMAL / PLAN）；PLAN 模式下 `toolRegistry.toJsonArray()` 只返回只读工具；`Main` 加一个 `/plan` 命令切换。

---

#### 4.7 Hooks 系统

**问题**：有些行为不能靠"请 LLM 记得"，必须每次都强制发生——自动格式化、敏感命令拦截、审计日志。

**原理**：工具执行前后的**事件总线**。Claude Code 的 12 个 hook 事件中，最核心的两个是 `PreToolUse`（可 deny）和 `PostToolUse`（可反馈文本回主循环）。Hook 是外部进程，用 stdin/stdout JSON 通信；退出码 0=放行，2=block。

**本项目最小落地**：`ReActLoop` 在工具执行前后广播事件；`hooks/HookDispatcher.java` 按配置的 matcher（tool name 正则）触发子进程；子进程退出码决定是否 block。配置放 `.agent/hooks.json`。

**注意区分**：Hooks 和权限系统功能相似，但 Hooks 是**用户可定制**，权限系统是**内建**。组合使用：权限系统做基础防线，Hooks 做定制化策略。

---

#### 4.8 TodoList / Task Tracking

**问题**：LLM 在多步任务中容易"忘记还有什么没做"。

**原理**：**把工作记忆外化**——给 LLM 一个 `TaskCreate` / `TaskUpdate` / `TaskList` 工具集，让它自己把任务清单维护在 Agent 的内存状态里。LLM 每轮会被提醒有待办事项，自动按顺序推进。TaskList 本身就是普通工具，但它改变了 LLM 的**规划行为**——这是值得拿出来讲的"提示工程设计"案例。

**本项目最小落地**：内存里维护一个 `List<Task>`；四个工具 `task_create` / `task_update` / `task_list` / `task_get`；每轮开始时 system prompt 拼接当前未完成任务概览。

---

#### 4.9 专用工具补齐（Edit / Grep / Glob / WebFetch / WebSearch）

**问题**：只有 `read_file` / `write_file` / `execute_command` 也能干活，但效率低、出错概率高。

**原因拆分**：
- **Edit**（diff-based）：`write_file` 把整个文件重写，风险大且消耗 token；Edit 只传 `old_string → new_string`，模型犯错概率低。
- **Grep / Glob**：比 `execute_command("grep ...")` 更稳——不用担心 shell 转义，不用担心跨平台差异。Grep 底层用 ripgrep 最快。
- **WebFetch / WebSearch**：Agent 常需查文档/查错误码，给它网页工具才真正可用。

**本项目最小落地**：Edit 基于 Java 原生 String.replace，先要求严格精确匹配；Grep/Glob 用 JDK `Files.walk` + 正则；Web 工具用任意 HTTP 库，WebSearch 接现成的 Search API（DuckDuckGo 免 key 或 Brave Search）。

---

#### 4.10 Session 持久化 & Resume

**问题**：本项目 Session 关闭即丢。Codex 用 SQLite 存 thread，Claude Code 用 JSONL 快照，都能 resume。

**原理**：就是把 `ConversationHistory.messages` 落地。关键是**同时落"系统级决策点"（compact 摘要、工具 allowlist 变更）** 才能完整复原。

**本项目最小落地**：每条消息追加到 `sessions/{session-id}.jsonl`；`Main` 加 `/resume` 命令；SQLite 可忽略，简单文件就够演示原理。

---

### P2 — 生态与边界，了解即可

这些概念在技术分享中用"知道有这事 + 一段话说清原理"即可，不必都实现。

#### 4.11 Prompt Caching
Anthropic 的 `cache_control` 可把长期不变的 system prompt / 工具定义缓存 5 分钟（服务端），命中后费用打 10 折。本项目**用 OpenAI 兼容 API 暂时无法演示**，但知道这个优化点存在很重要。分享可讲："为什么 Claude Code 把大 CLAUDE.md 放文件开头——因为前缀缓存按 prefix 匹配。"

#### 4.12 Extended Thinking
Claude 的"思考 token"是专门的隐藏消息块，不算在可见 output 里，但会吃 token 预算。适合架构决策、长链路推理；简单 task 开了反而浪费钱。

#### 4.13 多模型路由（Model Tiering）
用 Haiku 4.5 跑 Subagent、Sonnet 4.6 主力、Opus 4.7 处理高风险——成本优化的主流姿势。抽象 `ModelRouter` 即可，一两百行 Java。

#### 4.14 多模态输入
OpenAI/Anthropic 都支持 message `content` 改成数组，混合 `text` 和 `image_url`。本项目改造成本低，但跟"学 Agent 原理"关系不大。

#### 4.15 Slash Commands
`/clear`、`/model`、`/help` 这类都是 **REPL 层**的本地快捷方式，不是模型能力。实现方式就是在读到 `/xxx` 时短路不进 ReAct 循环。

#### 4.16 IDE 集成 / LSP Bridge
Claude Code 的 VS Code 扩展、Codex 的 JetBrains 插件——底层都是 IPC + 事件桥接。本项目作为 CLI 工具，这块建议只做**概念介绍**，不实现。

---

## 5. 分阶段学习路线

### Sprint 1：原理强化（1-2 周）— 对应 P0

目标：把 P0 的四项都做出**演示级实现**，每项一次 commit，配一篇 `docs/` 下的 markdown 技术笔记（约 1500 字）。

- Week 1：4.1 上下文压缩 + 4.2 权限系统
- Week 2：4.3 Subagent + 4.4 MCP

总代码预计不超过 1000 行 Java。每做完一项能独立做一次 30 分钟的内部分享。

**输出**：4 个功能 + 4 篇技术笔记 + 1 次内部预演。

### Sprint 2：工程化（2-3 周）— 对应 P1

目标：让项目"真正能用"。

- Week 1：4.5 流式 + 4.6 Plan Mode
- Week 2：4.7 Hooks + 4.8 TodoList
- Week 3：4.9 专用工具（Edit/Grep/Glob 一起做）+ 4.10 Session Resume

**输出**：一个"能日常写代码"的 CLI Agent。

### Sprint 3：拓展（按需）— 对应 P2

挑 1-2 项做概念验证：建议 4.13（多模型路由，代码少收益高）+ 4.15（Slash Commands，提升交互质量）。其他保留为"延伸阅读"。

---

## 6. 内部分享建议

几点提醒：

1. **每项 P0 能力都可以独立分享**，不必等整个项目完工。
2. **分享目标是"让听众能自己画出时序图"**，而不是"我讲过 xxx"。
3. **避免"教程体"**：不是"我做了 xxx 的教程"，而是"xxx 这个设计为什么这样做 + 我们用最少代码演示它"。
4. **强烈推荐 Live Coding 风格**：现场从 `main` 分支切到一个 feature 分支，把核心修改的 30 行代码当场加进去——这比 slide 有说服力一百倍。
5. **每一章的 last slide 放"这个项目里 xxx 类/xxx 行就是这件事"**——让听众能自己 clone 下来继续读源码。

---

## 7. 参考资料

### 官方文档

- Claude Code Docs（[code.claude.com/docs](https://code.claude.com/docs/en/hooks)）：Hooks / Subagents / Skills / Sandboxing 全部有官方 reference
- OpenAI Codex CLI Docs（[developers.openai.com/codex/cli](https://developers.openai.com/codex/cli)）：CLI 用法、feature 说明、changelog
- MCP 官方（[modelcontextprotocol.io](https://modelcontextprotocol.io/docs/getting-started/intro)）：协议规范、SDK、示例 server
- Cursor Docs（[cursor.com/product](https://cursor.com/product)）

### 原理类

- Anthropic — [Context Engineering Cookbook](https://platform.claude.com/cookbook/tool-use-context-engineering-context-engineering-tools)
- Anthropic — [Multi-Agent Research System](https://www.anthropic.com/engineering/multi-agent-research-system)
- Anthropic — [Claude Code Sandboxing](https://www.anthropic.com/engineering/claude-code-sandboxing)
- Cursor — [Implementing a secure sandbox for local agents](https://cursor.com/blog/agent-sandboxing)
- 学术：《Building AI Coding Agents for the Terminal: Scaffolding, Harness, Context Engineering, and Lessons Learned》（2026-03 arxiv）

### 高质量博客

- alexop.dev — [Understanding Claude Code's Full Stack: MCP, Skills, Subagents, and Hooks](https://alexop.dev/posts/understanding-claude-code-full-stack/)
- penligent.ai — [Inside Claude Code, The Architecture Behind Tools, Memory, Hooks, and MCP](https://www.penligent.ai/hackinglabs/inside-claude-code-the-architecture-behind-tools-memory-hooks-and-mcp/)
- practiceoverflow — [Compaction: The Hidden Trick That Keeps AI Coding Agents from Forgetting Everything](https://practiceoverflow.substack.com/p/compaction-the-hidden-trick-that)
- Spring 官博 — [Agentic Patterns Part 7: Session API](https://spring.io/blog/2026/04/15/spring-ai-session-management/)

### 参考开源实现

- [openai/codex](https://github.com/openai/codex)（Rust，阅读门槛中）
- [lastmile-ai/mcp-agent](https://github.com/lastmile-ai/mcp-agent)（Python，MCP 客户端最小实现范例）
- [disler/claude-code-hooks-mastery](https://github.com/disler/claude-code-hooks-mastery)（Hooks 实战示例集）

---

## 附录：为什么不追平"产品级能力"

这个项目的定位是**学习**。Claude Code 背后是 Anthropic 整个团队的 2 年积累，追平产品能力既不现实也不必要。

我们的目标：**用尽可能少的代码，把每一个概念都打穿一次**。做完 P0，你能给同事讲清楚"为什么需要压缩、为什么需要 MCP、为什么需要 Subagent"；做完 P1，你能让项目成为日常能用的 CLI 工具；P2 里挑感兴趣的做即可。

保持"小、清晰、可读"才是本项目的护城河。
