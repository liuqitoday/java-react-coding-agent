# 通过手写 Coding Agent 了解 Agent 相关概念和原理

> 内部分享配套文档 · 2026-04
> 配套项目：本仓库（`java-react-coding-agent`，~1500 行 Java，零 AI 框架依赖）
> 配套 Demo：现场让 Agent 写一个贪吃蛇
> 配套私稿：`docs/presentation-script.md`（30 分钟口述脚本）

本文是一篇**学习型参考文档**，可以脱离分享独立阅读。结构上分 6 个 Part：

- Part 1 建立 Agent 的基础概念（什么是 Agent / Coding Agent / 生态）
- Part 2-4 顺着"核心架构 → 上下文工程 → 扩展与治理"三层，讲清主流 Coding Agent 的原理骨架
- Part 5 汇总实战陷阱
- Part 6 总结与继续学习入口

其中带 ⭐ 的章节是**本项目暂未实现但必须理解**的关键能力（上下文压缩 / Subagent / MCP / Hooks）——文档里作原理深讲，将来可以作为 roadmap 指引。

---

## 前言：为什么做这个项目

### 起点

- 想搞清楚 Claude Code / Codex CLI / Cursor 这些 Coding Agent 内部是怎么工作的
- LangChain / Semantic Kernel / Spring AI 这类框架封装太深，看不到本质
- 决定从零搭一个——用尽可能少的代码演示尽可能多的原理

### 三条约束

- Java 17，零 AI / Agent 框架依赖（只用 Gson + JDK HttpClient）
- 全项目 < 1500 行 Java
- "代码即文档"——每个类只做一件事，注释讲原理

### 现状

- 25 个 Java 文件，覆盖 8 个核心能力
- **已实现**：ReAct / Function Calling / AGENTS.md / Skill / TodoList / Edit 工具 / Cache-aware 注入 / 权限系统
- **未实现但写了知识文档**：Subagent / 上下文压缩 / MCP / Hooks / Plan Mode

---

# Part 1 · Agent 基础概念

> 这部分**不涉及本项目代码**，目的是让没接触过 Agent 的同事也能对齐到同一个基础概念。已经了解 Agent 的可以快速跳读。

## 1.1 什么是 AI Agent

**定义**：AI Agent 是一类能够**感知环境、推理决策、采取行动**并从结果中调整策略的系统，通常以大语言模型（LLM）作为"大脑"。

**和单纯 LLM 的区别**：

- **LLM** 是"输入文本 → 输出文本"的纯函数——一次问答，用完即止
- **Agent** 是"输入任务 → 循环地（推理 + 调工具 + 观察结果）→ 达成目标"的**循环系统**

经典公式：

```
Agent = LLM + Tools + Memory + Loop
```

### Agent vs 其他相关概念

| 维度 | Chatbot | RPA | Workflow Engine | Agent |
|---|---|---|---|---|
| 任务定义 | 自然语言对话 | 脚本录制 | 预定义 DAG | 自然语言目标 |
| 决策方式 | 单轮应答 | 确定脚本 | 预置分支 | LLM 运行时决策 |
| 适应新情况 | 不适应 | 不适应 | 有限分支 | 动态适应 |
| 工具使用 | 一般没有 | UI 操作 | 预定义调用 | 动态发现 + 调用 |

**关键区分**：Agent 的核心不是"能聊天"，而是"基于 LLM 推理做**动态决策**，并通过**调用工具**改变环境"。

### Agent 的四大核心能力

1. **感知（Perception）**：读文件、列目录、接收用户输入、拉取 API 响应
2. **推理（Reasoning）**：思考下一步做什么、选哪个工具、如何拆解大任务
3. **行动（Acting）**：调用工具改变环境——写文件、执行命令、调外部 API
4. **记忆（Memory）**：会话内的工作记忆（TodoList、激活的 Skill）；跨会话的长期记忆（AGENTS.md）

这四项是 Agent 的骨架——本项目的每个模块几乎都对应其中某一项。

## 1.2 什么是 Coding Agent

**Coding Agent 是 Agent 在软件工程场景的特化版本**：

- **感知工具**：`read_file` / `list_files` / `grep`（代码搜索）
- **行动工具**：`write_file` / `edit_file` / `execute_command`（跑测试、git 操作）
- **领域知识**：AGENTS.md 里的项目约定、编程语言语法、设计模式
- **典型任务**：写新功能、修 bug、重构、代码审查、生成测试

### Coding Agent 和通用 Agent 的关键差异

| 维度 | 通用 Agent | Coding Agent |
|---|---|---|
| 输出媒介 | 文本 / 结构化数据 | 代码文件（有语法、依赖） |
| 错误反馈 | 靠用户判断 | 编译器 / 测试 / 类型检查 |
| 验证闭环 | 弱（靠用户主观） | 强（能跑测试自证） |
| 典型时长 | 一次应答 | 多轮迭代（一个 PR 可能几十轮） |

**"代码反馈环"是 Coding Agent 强于通用 Agent 的根本原因**——Agent 可以自己跑测试/类型检查获得**客观反馈**，不依赖用户的主观评价。这让长任务能稳定收敛。

## 1.3 2026 年 Coding Agent 生态全景

**四家主流实现**（按影响力排序）：

| 工具 | 发布方 | 形态 | 代表性特色 |
|---|---|---|---|
| Claude Code | Anthropic | 终端 CLI + IDE | Hook / Skill / Subagent 三大扩展体系 |
| Codex CLI | OpenAI | 终端 CLI | 路径寻址 subagent、TOML 配置、GPT-5.4 系列 |
| Cursor | Cursor 公司 | IDE | Background Agent（云 VM 最多 8 个并行） |
| Windsurf | Codeium | IDE | Flow 架构、实时代码感知 |

**它们的共性**：都实现了 ReAct 循环、Function Calling、AGENTS.md、权限系统、上下文压缩、Subagent、MCP。

**为什么强**：不是"单个功能"厉害，而是这一套概念**组合在一起**形成了完整的 Agent 平台——**每项能力都能通过具体用例体会其必要性**，这正是本项目想还原给你的。

## 1.4 为什么还要从零写一个

**看穿原理的价值**：

- **现有框架封装太深**：LangChain / Semantic Kernel / Spring AI 这些——你看到的是框架抽象，看不到底层原理
- **原理之间相互影响**：例如 Cache-aware Design 会反过来约束你怎么设计 TodoList、怎么设计 Skill——不动手写一遍很难理解这种互动
- **理解原理 = 更好的使用习惯**：看了原理，你会知道：为什么 system prompt 不能塞动态内容、为什么大任务该用 subagent、为什么权限规则不能依赖 LLM 自觉

**本项目的承诺**：所有原理的代码实现都能在 100 行 Java 内找到对应位置。25 个文件、<1500 行代码——读完一次，心智模型就建立起来了。

---

# Part 2 · 核心架构

## 2.1 ReAct 循环（Reasoning + Acting）

### 核心思想

LLM 不是一次给答案，而是**循环**：

```
[Thought] 我应该读 pom.xml 看看依赖
[Action]  read_file({"path": "pom.xml"})
[Observe] <pom.xml 内容>
[Thought] 现在我可以回答了
[Answer]  这个项目依赖 Gson 2.13.x ...
```

### 本项目实现

| 文件 | 职责 |
|---|---|
| `agent/core/ReActLoop.java:51-136` | 整个循环（130 行就够了） |
| `agent/core/ConversationHistory.java` | 4 角色消息管理（system / user / assistant / tool） |
| `agent/tools/Tool.java` | 工具接口（4 个方法） |

### 一个关键 trade-off

每一轮 LLM 调用都要把**所有历史消息**重发一遍——LLM 没有持久记忆。

→ 长对话里 token 反复付费
→ Part 3 的**上下文工程**就是在解这个问题的不同切面

## 2.2 OpenAI Function Calling

### 请求体结构

```json
{
  "model": "deepseek-chat",
  "messages": [
    {"role": "system", "content": "你是一个编码 Agent..."},
    {"role": "user",   "content": "读 pom.xml"}
  ],
  "tools": [{
    "type": "function",
    "function": {
      "name": "read_file",
      "description": "...",
      "parameters": { ... JSON Schema ... }
    }
  }]
}
```

### 响应里的 tool_calls

```json
{
  "message": {
    "role": "assistant",
    "tool_calls": [{
      "id": "call_abc",
      "function": {
        "name": "read_file",
        "arguments": "{\"path\":\"pom.xml\"}"
      }
    }]
  }
}
```

### 关键点

- 不是让 LLM 用自然语言"说"想调用什么——LLM **直接返回结构化 JSON**
- 工具的 schema **不是 prompt 描述，是协议层的字段**
- 所有 OpenAI 兼容 API（DeepSeek / Moonshot / 通义 / Ollama 多数模型）都支持

### 本项目实现

- Tool → Schema 自动转换：`agent/tools/ToolDefinition.java`（30 行）
- 工具注册：`agent/tools/ToolRegistry.java`
- HTTP 客户端：`agent/llm/LLMClient.java`（90 行，纯 JDK HttpClient）

**新加一个工具的成本**：实现 `Tool` 接口 4 个方法，在 `ToolRegistry` 里注册一行。**20 行 Java 以内**。

---

# Part 3 · 上下文工程（Context Engineering）

## 3.1 为什么说上下文工程是 Agent 的灵魂

**核心观察**：现代 Agent 的竞争力不在于"用了什么 LLM"，而在于"往 context 里**塞了什么、怎么塞、什么时候塞**"。

Context Engineering 面对的三个本质挑战：

- **Context Window 是最稀缺的资源**：Sonnet 4.6 200K token、Codex GPT-5.4 默认 272K——听起来很多，一个读 50 个文件的任务就能耗尽
- **每轮都是全量输入**：LLM 没有持久记忆，每次调用要把所有历史消息**重新发一遍**（Prompt Cache 只减少计算量，不改变"必须随包发送"的事实）
- **关键信号会被稀释**：长 context 里，真正重要的信息被淹没在海量工具输出中；多篇 2025-2026 研究表明 context 涨了但任务质量**不涨甚至下降**

三类应对策略，构成了本 Part 的 5 个小节：

- **怎么存**：3.2 AGENTS.md（跨会话）、3.4 TodoList（会话内工作记忆）
- **怎么省**：3.3 Skill 渐进披露、3.6 Cache-aware Design
- **怎么管**：3.5 上下文压缩

## 3.2 项目记忆：AGENTS.md

### 设计目的

让 Agent 知道"这是什么项目、有什么约定、要注意什么"——避免每次启动重复说背景。

### 实现规则（来自 Agentic AI Foundation 开放标准）

- 从 git root → cwd 逐级扫描 AGENTS.md
- 每级文件优先级：`AGENTS.override.md` > `AGENTS.md`
- 总大小限制 32KB，超出截断
- 加载内容拼接到 system prompt

### 本项目实现

- 加载器：`agent/memory/AgentsFileLoader.java`
- 数据模型：`agent/memory/ProjectContext.java`

### 行业对齐

| 工具 | 对应文件名 |
|---|---|
| Codex CLI | `AGENTS.md` |
| Claude Code | `CLAUDE.md`（兼容 AGENTS.md） |
| Gemini CLI | `GEMINI.md` |
| 本项目 | `AGENTS.md`（标准实现） |

## 3.3 Skill：渐进披露（Progressive Disclosure）

### 问题

想给 Agent 加几十个领域技能（PR 审查 / 代码生成 / 安全扫描 / ...）——全塞进 system prompt 每次都要发几万 token。

### 解法

- 启动时只读每个 skill 的 frontmatter（约 100 token：`name` + `description`）
- 只把"目录"放 system prompt
- LLM 判断需要某个 skill 时，调 `activate_skill` 工具加载完整内容

### 本项目实现

| 文件 | 职责 |
|---|---|
| `agent/skills/SkillRegistry.java` | 启动扫描 `.agents/skills/` |
| `agent/skills/SkillLoader.java` | 激活时按需加载正文 |
| `agent/skills/FrontmatterParser.java` | 简易 YAML 解析（不引入外部依赖） |
| `agent/tools/ActivateSkillTool.java` | 给 LLM 调的入口 |

### 类比

像 IDE 的"按需加载插件"——不打开就不消耗内存。

## 3.4 外部工作记忆：TodoList

### 现象

LLM 在多步任务里容易"忘事"——做到一半丢步骤。

### 解法

给 LLM 一个 `todo_write` 工具，让它把任务清单维护在 Agent 内存里。每轮**自动注入到 prompt**，LLM 持续看到自己的计划。

### 关键设计：单工具 + 全量替换

```
todo_write({
  todos: [
    { content: "Create index.html", activeForm: "Creating index.html",
      status: "completed" },
    { content: "Add CSS styling",   activeForm: "Adding CSS styling",
      status: "in_progress" },
    { content: "Write game loop",   activeForm: "Writing game loop",
      status: "pending" }
  ]
})
```

### 本项目实现

| 文件 | 职责 |
|---|---|
| `agent/todo/TodoItem.java` | record，3 个状态常量 |
| `agent/todo/TodoList.java` | 会话级状态 + `toPromptSection()` 渲染 |
| `agent/tools/TodoWriteTool.java` | 工具实现（含三项校验：非空 / status 合法 / 最多一个 in_progress） |

### 真正改变的不是"功能多了一个"

是 LLM 的**规划行为**被强化——看到自己的清单就不会跳步。这类"通过外部状态改变 LLM 行为"的设计，在 Agent 工程里叫"**外部工作记忆**"。

## 3.5 上下文压缩（Context Compaction）⭐

> **本项目未实现**，但这是 Agent 在真实场景能不能用下去的生死线。放在这里**作原理深讲**。

### 问题：长 session 必然爆 context

Claude Code 内部统计：一个 3 小时的 coding session 平均消耗 300K+ token——远超 Sonnet 4.6 的 200K 窗口。

硬上限到了以后的三种选择：

| 选择 | 代价 |
|---|---|
| A. 拒绝继续 | 用户体验极差 |
| B. 丢弃最早的历史 | 丢掉关键决策上下文 |
| C. **LLM 总结旧历史，保留决策要点** | ← Claude Code / Codex 采用 |

### Claude Code 的 Auto-Compact 机制

- **触发条件**：context 使用率 ≥ 95%
- **压缩方式**：用专门的 summarization prompt 调 LLM，生成"本次对话摘要"，替换前 N-K 轮
- **保留内容**：关键决策、用户偏好、当前任务状态
- **丢弃内容**：具体 tool 输出（如 read_file 全文）、中间推理

### Turn-safe 边界——最重要的实现细节

绝不能切断 `tool_call` 和 `tool_result` 的对应关系。OpenAI 协议要求每个 `tool_call_id` 必须有对应的 `tool` 角色消息——如果压缩时把 `tool_call` 留下但 `tool_result` 扔了（或反过来），下一轮请求会收到 400 错误。

**正确做法**：压缩以**轮**为边界，一轮 = "user 消息 → assistant 消息（可能带 tool_calls）→ 所有对应的 tool_result"；要么整轮保留，要么整轮压成摘要。

### Clipping vs Summarization

- **Clipping（截断）**：直接删除最早的 N 条消息——简单但丢信息
- **Summarization（总结）**：用 LLM 把早期历史压成 summary——成本高但保留要点
- **混合策略（主流）**：近 K 轮保留原样 + 之前的压成摘要

### 本项目若日后实现（最小可行版本）

1. `ConversationHistory` 增加 token 估算（简单按 1 token ≈ 4 字符估算即可起步）
2. 新建 `agent/core/Compactor.java`：
   - 取 history 前 `size - keepRecent` 条消息
   - 调 LLM 生成摘要（用专门的 summarization prompt）
   - 把摘要作为一条 system message 替换被压缩的消息
3. 在 `ReActLoop.run()` 开头检查 token 数，超阈值就调 compactor

### 坑

- **不要在 subagent 里触发压缩**——会把子 Agent 的工具输出压丢
- **压缩后下一轮的第一件事**应该是 LLM 基于摘要恢复任务——不是直接继续工具调用
- **压缩结果本身又超阈值怎么办**：用更激进的策略（例如只留 title），或者直接 fail-fast 提示用户重启

## 3.6 Cache-aware Design ⭐（最重要的设计原理）

### 问题：踩过的坑

最初实现 TodoList 时，我把"当前任务清单"拼到 `SystemPromptBuilder.build()` 末尾——看起来超级自然，LLM 每轮都能看到最新状态。

但走查发现这个设计会让 **Prompt Cache 命中率雪崩**。

### 原理：KV-Cache 是字节级前缀匹配

```
请求 = [tools] + [system message] + [user/assistant/tool messages...]
                       ↑
        如果末尾有变 → 整个 system 之后全部 cache miss
```

LLM 推理时，每层算出的 KV 张量按 prefix hash 缓存。**前缀字节有任何变化，从那位开始往后的所有 KV 必须重算。**

### 数据感知

| 提供方 | 缓存命中折扣 |
|---|---|
| OpenAI | cache read ≈ 50% 价 |
| Anthropic | cache read = 10% 价 |
| DeepSeek | cache hit ≈ 10-20% 价 |

→ system 前缀稳定 vs 不稳定，长 session 成本相差 5-10 倍

### 解法：Claude Code 的 `<system-reminder>` 模式

把动态状态作为 `<system-reminder>` 块**追加到本轮最后一条 tool_result 末尾**：

```
[system message]                     ← 永远静态 → cache 长期命中
[user 1]
[assistant + tool_calls]
[tool_result A]
[tool_result B + <system-reminder>]  ← 只有这里是新内容
```

tool_result 本来就是每轮新增的，在它末尾追加内容**零额外成本**。

### 本项目实现

| 文件 | 职责 |
|---|---|
| `agent/core/StateReminder.java` | 收集动态状态（TodoList + 已激活 Skill） |
| `agent/core/ReActLoop.java:113-120` | 注入点（本轮最后一条 tool_result 才加） |
| `agent/core/SystemPromptBuilder.java` | 故意只放静态内容（base + AGENTS.md + Skill 目录） |

### 推广：什么内容不能放 system

时间戳 / session id / 用户信息 / 外部状态 / 当前 git branch / 当前 cwd / **任何每轮变化的内容**。

> 详细原理见 `docs/prompt-cache-aware-design.md`（4500 字深讲）

---

# Part 4 · 扩展机制与安全治理

> 这个 Part 讲的都是"给 Agent 加边界的能力"。**其中只有权限系统在本项目里实现了**；Subagent / MCP / Hooks 在本项目未实现，但作原理深讲——因为它们是 2026 年 Agent 生态的事实标配。

## 4.1 权限系统：从工具层画安全边界 ✓

### 问题

`execute_command` 直接 `/bin/sh -c`，没有任何拦截——LLM 一旦想 `rm -rf /` 就炸了（真实事故案例存在）。

### 三态决策

| Decision | 行为 |
|---|---|
| ALLOW | 静默放行 |
| DENY | 直接拒绝，不询问用户 |
| ASK | 弹给用户 (y/N) |

### 配置示例（permissions.json）

```json
{
  "deny": ["execute_command:rm -rf", "execute_command:mkfs"],
  "ask":  ["execute_command:", "write_file:/etc/"]
}
```

### 关键设计

- **不靠 prompt 劝**——而是 **PreToolUse 闸门**，工具执行前拦截
- 命中 deny / 用户拒绝时，把"被拒绝"的事实**回写**给 LLM → LLM 自己决定下一步（换方式 / 告知用户 / 放弃）

### 本项目实现

| 文件 | 职责 |
|---|---|
| `agent/permission/Decision.java` | 三态枚举 |
| `agent/permission/PermissionPolicy.java` | 加载规则 + 子串匹配 |
| `agent/permission/PermissionGate.java` | 闸门 + ASK 交互 |
| 集成点 | `agent/core/ReActLoop.java:113` |

### 比 OS 沙箱差什么

权限系统拦不住 LLM 的"绕路攻击"（`rm -rf` 被拦就改用 `find -delete`）。

真正的护城河是 **OS 级沙箱**（macOS Seatbelt / Linux Landlock / Cursor 的云 VM）—— 但那超出学习项目范围。

## 4.2 Subagent：上下文隔离与任务委派 ⭐

> **本项目未实现**。完整深讲见 `docs/subagent-primer.md`（4500 字）。此处给出**要点**。

### 问题：Context Dilution

长对话里每个 token 都在反复付费。Agent 读几十个文件的任务，主 context 会被读出来的文件内容占爆——而最后你要的只是一段 500 字总结。

### 解法：上下文隔离

主 Agent 派一个**临时的、独立 context** 的子 Agent 去干活，子 Agent 干完只返回**摘要**。主 context 里只留"我派了任务 + 收到结果"两条消息，永远不膨胀。

### 核心金句

> *"Share memory by communicating, don't communicate by sharing memory"*

原出处 Go 社区 channel 的设计哲学，和 Erlang Actor 模型是一脉相承的思想——**不要通过共享内存来通信，而是通过通信来共享内存**。

移植到 Agent：子 Agent 不是"复制主 Agent 的 context 再加几条消息"，而是**从零新建一个 context**，只通过"任务描述 → 结果摘要"两条消息与主 Agent 通信。

### 三条硬边界

1. **独立 context**：子 Agent 的 history 和主 Agent 的 history 互不可见
2. **独立工具集**：按任务类型裁剪——reviewer 只要只读、refactorer 要 edit、tester 要 execute_command
3. **禁止递归**：子 Agent 不能再派子 Agent——**硬性实现方式**是子 Agent 的 `ToolRegistry` 里不包含 `delegate_to_subagent`（不是靠 prompt 劝模型）

### 附加收益：模型成本分层

- 子 Agent 可以用**更便宜的模型**做机械活（Haiku 4.5 / Codex-mini）
- 主 Agent 用 Opus/Sonnet 做规划和最终决策
- 按 2026 公开定价，这种分层可以把整体 token 成本压到 **1/3 ~ 1/5**

### 行业实现对比

| 工具 | 定义方式 | 隔离粒度 | 并行数 |
|---|---|---|---|
| Claude Code | `.claude/agents/<name>.md`（markdown + frontmatter） | Context | 主 + 3 子 |
| Codex CLI | `config.toml [agents]` 段 | Context + OS 沙箱 | 多个（显式触发） |
| Cursor | Background Agent（云 VM + 独立 git 分支） | Context + 文件系统 | 最多 8 |

### 本项目若日后实现（约 150-200 行 Java）

1. 新建 `agent/tools/SubagentTool.java` 实现 `Tool` 接口
2. `execute(args)` 内部构造独立的：
   - `ConversationHistory`（全新）
   - `ToolRegistry`（**不含 SubagentTool** —— 从工具集层面断绝递归）
   - `ReActLoop`（更严格的迭代上限，如 10 轮）
3. 运行 subLoop，把最后一条 assistant content 作为 observation 返回主 Agent
4. 可观察性：独立日志文件、独立 token 计数、蓝色渲染区分主/子

## 4.3 MCP（Model Context Protocol）⭐

> **本项目未实现**。这一节作概念 + 生态介绍。

### 背景

- 2024-11 Anthropic 提出
- 2025 年被 OpenAI、Google 接纳
- 2025-12 **捐给 Linux 基金会**，成为开放标准

### 一句话理解

**USB-C for AI tools**——一个 MCP Server 实现一次，所有支持 MCP 的 Agent 都能用。

### 解决的问题

MCP 之前的世界：每家 Agent 都要自己实现工具——Slack 集成，Claude Code 写一套、Codex 写一套、Cursor 写一套。**N 家 Agent × M 个集成 = N × M 工程量**。

MCP 之后：M 个 MCP Server 实现一次，所有 Agent 都能接。**N + M 工程量**。

### 三个角色

- **Host**：运行 Agent 的环境（Claude Code、Codex CLI）
- **Client**：Host 里负责和 MCP Server 通信的模块
- **Server**：暴露工具/资源/prompt 的外部进程

### 三种能力

- **Tools**：LLM 可调用的函数（**最常用**）
- **Resources**：LLM 可读取的文档 / 文件
- **Prompts**：预定义的 prompt 模板

### 传输层

- **stdio**：Server 作为子进程，stdin/stdout 通信（本地工具场景）
- **Streamable HTTP**：远程服务（企业场景）

### 协议层

基于 **JSON-RPC 2.0** —— 和 LSP（Language Server Protocol）同一个协议族。设计时借鉴了 LSP 在 IDE 生态的成功。

### 典型 MCP 生态

- `mcp-server-filesystem` —— 文件系统 tools
- `mcp-server-github` —— GitHub API 封装
- `mcp-server-slack` —— Slack 消息推送
- `mcp-server-postgres` —— 数据库查询
- `mcp-server-puppeteer` —— 浏览器自动化

Anthropic 官方仓库有几十个社区实现。

### 本项目若日后实现

最小 MCP Client（约 200 行 Java）：

1. 启动 MCP Server 作为子进程（`ProcessBuilder`）
2. 通过 stdin/stdout 发送 JSON-RPC 请求（`tools/list`、`tools/call`）
3. 解析响应的 tools 列表，**动态注册**到 `ToolRegistry`
4. LLM 调用这些工具时，透传到 MCP Server 执行并把结果原样返回

起步阶段**只实现 tools**，resources/prompts 可以后续加。

### 为什么 2026 必须懂 MCP

Agent ↔ 外部工具的**开放标准已经确立**。不懂 MCP 等于不懂 2026 年的 Agent 生态——就像 2015 年不懂 REST 一样。

## 4.4 Hooks：工具执行的外部钩子 ⭐

> **本项目未实现**。这一节作概念介绍。

### 问题

权限系统内建了基本校验，但不够灵活。比如我想：

- 每次 `write_file` 后自动跑 `gofmt` / `prettier`
- 敏感操作（涉及 `.env`）自动给团队 Slack 发审批通知
- 每次 commit 后自动打 tag
- 工具执行失败时把错误写进外部日志系统

这些需求本质都是"工具执行前后做点事"——硬编码在 Agent 里既不灵活又不可定制。

### Claude Code 的 Hooks 设计

**四个 Hook 点**：

| Hook 点 | 触发时机 | 可以做什么 |
|---|---|---|
| `PreToolUse` | 工具执行前 | 阻止执行 / 修改参数 |
| `PostToolUse` | 工具执行后 | 转换结果 / 触发副作用 |
| `Stop` | 主 Agent 一次 prompt 处理完 | 日志归档 / 指标上报 |
| `SubagentStop` | 子 Agent 结束 | 结果后处理 |

### 实现原理

- 用户在 `.claude/hooks/` 下放**可执行脚本**（bash / Python / 任意语言）
- Claude Code 在 hook 点把上下文（工具名、参数、结果）通过 **JSON 写到脚本 stdin**
- 脚本 stdout 输出 JSON：`{"action": "allow" | "block", "modified_args": {...}, "message": "..."}`
- Claude Code 按脚本响应决定行为

### 与权限系统的区别

| 维度 | 权限系统 | Hooks |
|---|---|---|
| 内外 | Agent 内建 | 外部进程 |
| 可定制性 | 受限于配置格式 | 任意脚本逻辑 |
| 输入/输出 | 简单三态决策 | 完整 JSON 交互 |
| 性能 | 同进程零开销 | 需 fork 子进程（有成本） |

**两者并非替代关系**：权限系统负责"**简单常见的拦截**"（快、零配置），Hooks 负责"**复杂定制化需求**"（灵活、可编程）。

### 典型用途组合

```
PreToolUse:  lint / type-check / 敏感路径 Slack 审批
PostToolUse: 自动格式化 / 自动 git commit / 自动跑测试
Stop:        session 日志归档 / token 消耗指标上报
```

### 本项目若日后实现

最小 Hooks 系统（约 100 行 Java）：

1. `HookRegistry` 扫描 `.hooks/` 目录，按文件名识别 hook 点（如 `pre_tool_use.sh`）
2. `ReActLoop` 在工具执行前后调用对应 hook
3. Hook 通过 `ProcessBuilder` 启动子进程，JSON 通过 stdin/stdout 交换
4. 按 hook 响应决定：允许执行 / 阻止 / 修改参数 / 转换结果

---

# Part 5 · 实战经验

## 5.1 架构边界 > Prompt 边界

### 三个原理的统一思想

| 能力 | 边界画在哪 |
|---|---|
| 权限系统 | 工具调用层（PreToolUse 拦截） |
| Cache-aware 注入 | 消息结构层（动态状态走 tool_result） |
| Subagent 防递归 | 工具集合层（子 Agent 不含 SubagentTool） |

**统一心智模型**：

> **从架构层画边界，不靠 prompt 劝模型。**
>
> Prompt 是描述意图的，可以被 LLM 违反；架构是保证边界的，LLM 没法违反。

这条原则建议大家以后做任何 Agent 设计都记住——**能用架构解决的问题，别用 prompt 去劝模型**。

## 5.2 自己做 Agent 时的陷阱清单

> 这些坑我自己踩过，或者在调研 Claude Code / Codex 源码时看到别人踩过。

### 5.2.1 上下文工程（和 3.6 Cache-aware 一脉相承）

- **动态内容不能进 system prompt**（已详讲）
- **JSON Schema 字段顺序必须稳定**：Gson 的 `JsonObject` 序列化按**插入顺序**。千万别用 `HashMap` 构造 schema——两次生成的字节序列会不同，整个 `tools` 字段 cache 失效
- **`tools` 数组顺序必须稳定**：本项目用 `LinkedHashMap` 注册工具，顺序固定。**动态开关工具**（根据用户身份）会让整个缓存爆掉——请三思
- **任何每轮变化的值都走 `<system-reminder>`**：时间戳、session id、用户名字、外部系统健康状态、当前 git branch、当前工作目录

### 5.2.2 工具设计

- **description 写"何时使用"，不只是"是什么"**：
  - ✗ `"Read a file"`
  - ✓ `"Read a file when you need its contents. Prefer edit_file over reading and rewriting."`
- **错误消息是给 LLM 读的，不是给人读的**：
  - ✓ `"文件不存在：/tmp/x。建议先用 list_files 确认路径"`
  - ✗ `"java.nio.file.NoSuchFileException at ReadFileTool.java:52"`
- **参数防御性校验**：即使 JSON Schema 标了 `required: ["path"]`，代码里仍要处理 path 缺失、为 null、为空串。**LLM 不保证遵守 schema**
- **命名动词开头 + 明确**：`read_file` / `write_file` / `edit_file`——比 `file_reader` / `file_manager` 好
- **相似工具的边界要清晰**：避免 `write_file` 和 `edit_file` 功能重叠不清——description 里要写明"创建新文件用 write_file，修改已存在文件用 edit_file"

### 5.2.3 循环控制

- **`max_iterations` 必须有上限**：没有就会无限跑。常见的 ping-pong 场景：LLM 在两个工具之间横跳，每次都觉得"下一次会更好"
- **Temperature 在 Agent 场景要低**（0.0 ~ 0.2）：Agent 不需要创造力，需要**可复现**。很多人默认 0.7 结果 Agent 行为抖动
- **HTTP timeout 必须设**：connect timeout（~10s）和 read timeout（~60s+）分离。LLM API 偶尔卡 30s 以上
- **区分 `context_window` 和 `max_tokens`**：前者是输入上限，后者是输出上限。搞混了会看到奇怪的 400 错误

### 5.2.4 调试与可观察性

- **完整日志是救命稻草**：每次请求 / 响应都 pretty-print JSON 到文件——**90% 的 Agent bug 靠对比两次请求的 prompt diff 就能定位**
- **"LLM 为什么这样做"的答案**：80% 在 prompt 里——包括 system、AGENTS.md、tool description、history 的累积效应
- **每个工具调用都要有视觉反馈**：开发时你自己 debug 也需要——没反馈的循环调试起来很痛
- **重要决策点打"快照"**：cache 命中率、token 消耗、单轮耗时——分享 / 问题定位时都用得上

### 5.2.5 模型兼容性

- **不同模型对 Function Calling 遵守度不同**：Claude 最严格（`tool_call_id` 对齐要求高）、GPT-4o 居中、DeepSeek / Qwen 偶尔跳过必填字段或返回奇怪的 `arguments` 格式
- **换模型必须重跑"工具覆盖测试"**：不能假设"OpenAI API 兼容 = 行为兼容"。尤其是 `tool_calls` 数组和 `arguments` 字段
- **DeepSeek-chat 和 DeepSeek-coder 对工具的遵守度不一样**：实测有差异，切换前必须对比
- **`arguments` 字段是字符串不是对象**：OpenAI 协议要求 `arguments` 是 JSON 字符串——需要 `JsonParser.parseString(argsString)` 二次解析。新手常栽这里

### 5.2.6 心智模型（最容易忽视的那类问题）

- **Agent ≠ 传统 API**：一次用户输入可能触发 N 轮 LLM 调用、M 个工具执行。监控、计费、延迟度量方法**完全不同**
- **不要迷信 Function Calling 的"结构化"**：LLM 仍会传错参数、跳过字段。**代码层永远要有最后一道防线**（校验 + 容错）
- **Prompt 描述意图，架构保证边界**：本文核心金句——能用架构解决的问题，别用 prompt 去劝模型
- **大任务先让 LLM 拆解再做**：直接"帮我做 X"容易走偏；"先给我一个 todo 计划，用 todo_write 记录"往往更可靠
- **Agent 不是越"自主"越好**：Claude Code 的 permission 模式、Codex 的"explicit 触发 subagent"都在**约束自主度**——过度自主 = 过度不可控

### 5.2.7 一条通用心法

> **开发时把你自己当成 LLM**：对着当前 context（system + 历史），你知道下一步该做什么吗？
>
> - 看不清 → 是 prompt 没写好
> - 看得清但 LLM 不做 → 是工具定义没写好
> - 做错了方向 → 通常是工具集里缺了一个关键的
>
> 大多数 Agent 调试问题都能归到这三类之一。

---

# Part 6 · 总结与继续学习

## 核心三件事

1. **Agent 的核心是 ReAct + Function Calling**——14 个 Java 文件就够了
2. **上下文工程是 Coding Agent 的灵魂**——AGENTS.md / Skill / TodoList / Cache-aware / 上下文压缩都是同一类东西
3. **架构边界 > prompt 边界**——权限、防递归、防 cache 失效都是工具层 / 消息结构层的事

## 本项目的边界声明

**能做什么**：看穿现代 Coding Agent 的核心原理，每项原理都能在 100 行 Java 内找到实现。

**不能做什么**：上生产。没有重试、没有限流、没有并发控制、没有错误恢复、没有流式输出——这些是产品框架的核心能力，但和"看穿原理"的目标无关，所以刻意没做。

**规划代码上限**：P0/P1/P2 全部做完预计 **< 5000 行 Java**。如果超过 1 万行说明方向错了——"用最少代码演示原理"是这个项目的护城河。

## 继续学习的入口

### 本项目内

- **项目源码**：`src/main/java/agent/`（25 个文件，每个都短小）
- **调研全景**：`docs/2026-coding-agent-gap-analysis.md`（16 项能力的 P0/P1/P2 路线表）
- **Cache 原理深讲**：`docs/prompt-cache-aware-design.md`
- **Subagent 知识**：`docs/subagent-primer.md`
- **配套 30 分钟分享脚本（私稿）**：`docs/presentation-script.md`

### 外部延伸阅读

- **Anthropic 官方**：[Claude Code Docs](https://code.claude.com/docs) · [Multi-Agent Research System](https://www.anthropic.com/engineering/multi-agent-research-system) · [Prompt Caching](https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching)
- **OpenAI 官方**：[Codex CLI Docs](https://developers.openai.com/codex/cli/features)
- **MCP**：[Model Context Protocol Spec](https://modelcontextprotocol.io) · [Anthropic MCP 社区 Servers](https://github.com/modelcontextprotocol/servers)
- **AGENTS.md 标准**：[Agentic AI Foundation](https://agents.md)
- **思想源头**：[Go Effective Go](https://go.dev/doc/effective_go)（"Share memory by communicating" 首次成文）

---

# Q&A

[投屏停留]

---

# 致谢与参考

- Anthropic — Claude Code Docs / Multi-Agent Research System
- OpenAI — Codex CLI Docs
- Cursor — Background Agents 设计
- AGENTS.md / MCP — Linux Foundation Agentic AI Foundation
- 完整参考链接见 `docs/2026-coding-agent-gap-analysis.md` 第 7 节
