# Subagent（子 Agent）原理与设计要点

> 撰写日期：2026-04-19
> 文档定位：**知识储备**文档，本项目暂不实现 subagent。本文作为内部分享时的延伸阅读材料 / 日后回看参考。

---

## TL;DR

- **Subagent 的本质是"上下文隔离"**：主 Agent 派一个临时的、带独立 context 的子 Agent 去干活，干完只返回结论摘要
- **解决的核心问题**：Context Dilution（上下文稀释）—— 长对话里每个 token 都在反复付费，隔离后主 Agent 的 context 不再膨胀
- **一句话总结（Anthropic）**：*"Share memory by communicating, don't communicate by sharing memory"*——这句话最早来自 Go 社区对 channel 的设计哲学，和 Erlang Actor 也是同一思路
- **三条边界**：独立 context / 独立工具集 / 不能再派子 Agent（禁递归）
- **2026 主流做法**：Claude Code（markdown 配置定义）、Codex CLI（TOML 配置 + 路径寻址）、Cursor（云端 VM 最多 8 个并行）

---

## 1. 为什么需要 Subagent

### 1.1 问题：Context Dilution

Context Window 是 Agent 的"RAM"——但它有几个反直觉的性质：

- **每轮都是全量输入**：LLM 没有持久记忆，每次调用都要把所有历史消息重新发一遍；100 轮对话 = 100 次付费（即使有 Prompt Cache 也只减少计算，不减少"必须随包发送"的事实）
- **关键信号被稀释**：长 context 里，真正重要的信息被淹没在海量工具输出中；多篇 2025-2026 研究表明 SWE-agent 任务里 context 涨了但任务质量不涨
- **上限硬顶**：Sonnet 4.6 200K，Codex GPT-5.4 默认 272K（可扩 1M）——但扩展 context 意味着质量下降 + 成本飙升

典型场景：Agent 被要求"调研整个代码库"。如果主 Agent 自己做，它会 `read_file` 几十个文件，每个文件的全文都进到主 context；最后主 context 塞了上万行代码，而真正需要产出的只是一段 500 字总结。

### 1.2 解法：隔离 context，只传摘要

让子 Agent 在**自己的干净 context** 里做探索，结束时只返回总结。主 Agent 的 context 里只留"我派了一个活 + 我收到这份总结"两条消息，永远不膨胀。

这就是 subagent 的核心价值。

### 1.3 附加收益：模型成本分层

- 子 Agent 可以用**更便宜的模型**做机械活（Haiku 4.5 / Codex-mini）
- 主 Agent 用 Opus/Sonnet 做规划和最终决策
- 按 2026 公开定价，这种分层可以把整体 token 成本压到 1/3 ~ 1/5

---

## 2. 核心原理

### 2.1 上下文隔离

每个 subagent 有自己独立的 ConversationHistory。主 Agent 的历史和子 Agent 的历史**互不可见**。对应那句经典表述：

> *Share memory by communicating, don't communicate by sharing memory.*

移到 Agent 领域的含义：子 Agent **不是**"复制主 Agent 的 context 然后加几条消息"，而是**从头开始新建一个 context**，只通过"任务描述 → 结果摘要"这两条显式消息与主 Agent 通信。

### 2.2 委派-汇总协议

主 Agent 派活的规范格式：

```
主 → 子：task_description（自然语言，描述目标 + 可用资源 + 返回格式要求）
子 → 主：final_answer（自然语言总结 + 必要的关键证据）
```

中间发生的一切（子 Agent 调用了什么工具、读了什么文件、走了什么弯路）都**不跨边界**。这是设计的核心约束——一旦跨边界，隔离就失去意义。

### 2.3 禁止递归

**子 Agent 不能再派子 Agent**。理由：

- 递归容易无限套娃，token 和成本爆炸
- 深层子 Agent 的结果经过多层摘要，失真严重
- 调试困难：很难追踪"这条信息是哪一层丢的"

2026 年主流 Agent 全部明令禁止。**关键实现细节**：子 Agent 构造时，它的 ToolRegistry **不包含** `delegate_to_subagent` 这个工具。从根源上断绝，不是靠 prompt 劝模型"别套娃"。

> 这是 "**从工具层画边界，而不是从 prompt 层劝说**" 的典型例子——和权限系统的 Pre-ToolUse 校验、Cache-aware Design 的 `<system-reminder>` 注入是同一思想。

---

## 3. 行业主流实现对比

### 3.1 Claude Code（Anthropic）

- **定义方式**：`.claude/agents/<name>.md`——每个 subagent 一个 markdown 文件，YAML frontmatter 配置
- **frontmatter 字段**：`name` / `description` / `tools`（工具 allowlist）/ `model`（可单独指定更便宜的模型）
- **调用方式**：主 Agent 看到 `description`，自己决定是否调用；或用户直接 `/agent <name>`
- **边界**：`tools` 必须是白名单
- **2026-02 新增 Agent Teams**：多个 Agent **协作**而非严格隔离，通过 prompt 直接编排角色——这是另一种拓扑，不替代 subagent

### 3.2 OpenAI Codex CLI

- **定义方式**：`config.toml` 里的 `[agents]` 段
- **调用方式**：用户显式请求时才 spawn subagent（官方原话：*"Codex only spawns subagents when you explicitly ask it to"*）
- **2026-03 v2 升级**：
  - 路径寻址：`/root/agent_a`、`/root/agent_a/agent_b`
  - 结构化 inter-agent messaging（不再是纯文本）
- **官方诚实警告**：*"subagent workflows consume more tokens than comparable single-agent runs"*——承认 subagent 不是银弹，用错场景反而更贵

### 3.3 Cursor（Composer 2 架构）

- **Background Agents（2026 核心特性）**：最多 8 个并行，每个在**独立云端 VM + 独立 Git 分支**
- 完成后自动开 PR，带 demo 截图
- 和前两家最大的区别：**环境隔离**而非仅 context 隔离——子 Agent 连文件系统都是独立的，彻底消除"文件冲突"问题

### 3.4 三家对比

| 维度 | Claude Code | Codex CLI | Cursor |
|---|---|---|---|
| Subagent 定义 | markdown 文件 | TOML 配置 | 无显式定义（Composer 内建） |
| 触发方式 | 自动 / `/agent` | 需显式请求 | UI 点击 / Agent 模式自动 |
| Context 隔离 | ✓ | ✓ | ✓ |
| 文件系统隔离 | ✗ | ✗（OS 沙箱但 workspace 共享） | ✓（云端 VM） |
| 并行 | ✓（Agent Teams 可协作） | ✓ | ✓（最多 8 个） |
| 禁止递归 | ✓ | ✓ | 不适用（架构不同） |
| 可选独立 model | ✓ | ✓ | ✓ |

---

## 4. 设计细节

### 4.1 传什么给 subagent

**标准做法**：只传 task description + 必要的上下文锚点（如 cwd、文件路径）。

```
好 ✓：{ task: "Explore src/main/java/agent/ and summarize the architecture in 3 bullets",
         cwd: "/path/to/project" }

坏 ✗：{ parent_history: [...主 Agent 的所有消息...], task: "同上" }
```

把整个主 history 传给子 Agent 是"共享内存"式通信——token 成本反而爆炸，完全违背 subagent 的初衷。

### 4.2 子 Agent 的工具集设计

按任务类型裁剪。典型模式：

| Subagent 类型 | 推荐工具集 | 典型任务 |
|---|---|---|
| Explorer | `read_file` / `list_files` / `grep` | 代码调研、架构分析 |
| Reviewer | 只读 + 无外部 IO | PR 审查、安全审计 |
| Refactorer | 读 + `edit_file` + `grep` | 批量代码修改（但不执行命令） |
| Tester | 读 + `execute_command`（限定 test runner） | 跑测试、收集报告 |

**一条铁律**：**永远不给任何 subagent `delegate_to_subagent`**（防递归）。

### 4.3 返回协议

子 Agent 完成后，把它**最后一条 assistant message 的 content** 作为 subagent 工具的 observation 返回给主 Agent。其他中间消息全部丢弃。

这一点和普通工具的 return 不同——普通工具返回结构化结果，subagent 返回的是"LLM 生成的自然语言总结"。所以要**在 task description 里明确要求**：

> *"请在 final_answer 里用 3 段话总结：(1) 架构分层 (2) 核心模块 (3) 扩展点"*

否则模型可能返回 "我做完了！" 这种无信息量的总结。

### 4.4 并行 vs 串行

- **串行**（阻塞等待）：代码简单，一个 subagent 跑完再跑下一个；适合依赖链
- **并行**（fork-join）：主 Agent 同时派 N 个 subagent，等全部完成后收集结果；适合独立任务

**2026 主流做法**：Anthropic / OpenAI / Cursor 都默认允许并行，但有硬预算上限（Claude Code 默认主 + 3 子 = 4 并发；Cursor 最多 8 个 Background Agent）。

**若日后在本项目实现**：强烈建议先做串行版。并行涉及 CompletableFuture、错误聚合、部分失败处理，复杂度陡增——而对学习原理的加分很少。Anthropic 自己的 multi-agent researcher 最初也是串行版先稳定了才上并行。

### 4.5 扩展性的数学约束

有一篇 2026-01 的 arxiv 论文（*Phase Transition for Budgeted Multi-Agent Synergy*）给出了以下结论：

- **Star topology**（一主多子，主汇总）：agent 数量 N 会 saturate 在 `N ~ W / m`——W 是主 Agent 的 context window，m 是每条摘要的平均长度
- **Hierarchical tree**（分层树，b 分支、深度 L）：可扩展到 `N = b^L`

对学习项目来说只要理解**一件事**：**主 Agent 的 context 能装下多少子 Agent 的返回摘要，决定了你能并发多少个子 Agent**。如果子 Agent 平均返回 1000 token，主 Agent 预算 100K，理论上限大约 100 个——实际远低于此。

---

## 5. 防御性设计（Prod 级应加，学习版可简化）

### 5.1 递归禁止（**强制**）

子 Agent 的 ToolRegistry 构造时，**不注册** `delegate_to_subagent`。硬边界，不依赖 prompt。

### 5.2 预算/迭代上限

每个子 Agent 应有独立的 `maxIterations`，通常比主 Agent 更严格。推荐：

- 主 Agent：15 轮
- 子 Agent：10 轮（更聚焦，不应该走弯路太久）

### 5.3 超时

子 Agent 调用应该有**墙钟超时**（如 5 分钟）。超时直接 kill，返回错误给主 Agent——比让主 Agent 干等强。

### 5.4 错误传播

子 Agent 失败时（超迭代、LLM 报错、工具挂掉），**不要**把 stack trace 原样扔回主 Agent。给主 Agent 的错误应该是**自然语言**：

```
好 ✓：{ error: "子 Agent 无法完成任务：重试多次后仍无法读取指定文件" }
坏 ✗：{ error: "NullPointerException at ReActLoop.java:107" }
```

主 Agent 要基于错误做决策（改个方式重派 / 告诉用户放弃 / 换任务），它不处理 JVM 异常。

---

## 6. 日后若要在本项目落地的最小路径

> **再次强调：本项目暂不实现 subagent**。以下只为 future reference，保留一个可执行的最小设计思路。

**最小可行版本（约 150-200 行 Java）**：

1. 新建 `agent/tools/SubagentTool.java`，实现 `Tool` 接口
2. `execute(args)` 的核心伪代码：

```java
// 构造子 Agent（注意：用同一个 LLMClient，但独立的 history / tools / state）
ConversationHistory subHistory = new ConversationHistory(SUBAGENT_SYSTEM_PROMPT);
subHistory.addUserMessage(args.get("task").getAsString());

ToolRegistry subToolRegistry = new ToolRegistry();
// 关键 —— 刻意不调用 subToolRegistry.register(new SubagentTool(...))
// 从工具集层面断绝递归

StateReminder subReminder = new StateReminder(new TodoList(), new SkillSessionState());
ReActLoop subLoop = new ReActLoop(llmClient, subToolRegistry, subRenderer,
                                   10 /* 更严格的迭代上限 */,
                                   subReminder, subGate);

subLoop.run(subHistory);
return ToolResult.success(subHistory.lastAssistantContent());
```

**为了演示效果，需要顺手做的可观察性**：

- 独立子 Agent 日志文件（如 `logs/llm_xxx_subagent_N.log`）——和主 Agent 并排看 token 数差距
- ConsoleRenderer 新增 `renderSubagentStart()` / `renderSubagentEnd()`，颜色建议蓝色（区别于现有青/黄/绿/紫/红）
- 子 Agent 结束时打印 `"子 Agent 调用了 N 次工具，约 M tokens"`——让听众有量化感知

---

## 7. 延伸阅读

### 官方文档

- Claude Code — [Custom Subagents](https://code.claude.com/docs/en/sub-agents)
- OpenAI Codex — [CLI Features](https://developers.openai.com/codex/cli/features)（含"consumes more tokens"的诚实声明）
- Cursor — [Product / Agents](https://cursor.com/product)

### 原理与设计

- Anthropic — [Multi-Agent Research System](https://www.anthropic.com/engineering/multi-agent-research-system)
- *Share memory by communicating* 原出处：Go 官方博客 [Effective Go](https://go.dev/doc/effective_go)

### 学术

- *Phase Transition for Budgeted Multi-Agent Synergy*（2026-01, arxiv）—— 分析 star 和 hierarchical 两种拓扑的扩展性上限
- *BATS: Budget-Aware Tool Subagents*（2025-11）—— 引入预算追踪（HIGH / MEDIUM / LOW / CRITICAL 四档），在迭代上限内让整体成本下降 10x

### 思想源头

- **Erlang OTP** 的 Actor 模型——被广泛引用为 subagent 设计的思想源头
- **Unix philosophy**：*"Do one thing and do it well"* —— 每个 subagent 应该只专注一件事
- **Go channels**：*"Share memory by communicating"* 的首次成文出处

---

## 8. 分享时的口述要点

如果不做 live demo，把 subagent 作为 60-90 秒的"延伸话题"讲给同事听，可以直接用下面这段：

> *Subagent 解决的核心问题是 **context dilution**。长对话里每个 token 都在反复付费，Agent 读几十个文件就会把 context 撑爆。subagent 让主 Agent 派一个临时的、干净 context 的子 Agent 去干脏活，只收一段总结——主 Agent 的 context 永远保持精简。*
>
> *原理金句是 Anthropic 那句 **"Share memory by communicating"**——其实这句话来自 Go 社区，和 Erlang Actor 是一脉相承的设计哲学。*
>
> *Claude Code、Codex CLI、Cursor 三家 2026 年都实现了 subagent，细节各不相同——Cursor 最激进，直接云端 VM 隔离，一次跑 8 个并行 Agent 还能自动开 PR。*
>
> *一个重要的非平凡约束：**子 Agent 不能再派子 Agent**。三家都明令禁止，实现方式统一是"子 Agent 的工具集里不包含 delegate_to_subagent"——**从工具层画边界，不靠 prompt 劝模型**。这个思想和我们在这个项目做的权限系统、cache-aware 注入是一个路数。*
>
> *本项目暂不实现 subagent。不是因为不重要，是因为贪吃蛇这种小任务看不出它的价值——它的好处要在真正大型 context 场景才显现。如果感兴趣，文档里有最小落地路径。*

---

## 附：和本项目其他原理的串联

Subagent 和本项目已落地的几个能力是**同一设计哲学的不同切面**：

| 能力 | 边界在哪画 | 对应本文的哪个观察 |
|---|---|---|
| **权限系统** | Pre-ToolUse 校验（在工具执行前拦截） | 从工具调用层画边界，不靠 prompt 劝模型别做 |
| **Cache-aware 注入** | 动态状态放 tool_result 末尾，不放 system | 从消息结构层画边界，不靠 prompt 管理缓存 |
| **Subagent 防递归** | 子 Agent 的 ToolRegistry 不含 SubagentTool | 从工具集层画边界，不靠 prompt 禁止递归 |

**统一心智模型**：*Agent 原理的关键不在于"怎么写好 system prompt"，而在于"在架构层画对边界"*。prompt 是描述意图的，架构是保证边界的——前者可以被 LLM 违反，后者不行。
