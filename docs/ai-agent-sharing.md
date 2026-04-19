# 用 1500 行 Java 看穿 Coding Agent 的内部原理

> 内部分享 · 2026-04
> 配套项目：本仓库（`java-react-coding-agent`）
> 配套 Demo：现场让 Agent 写一个贪吃蛇

---

## 议程（30 分钟）

| 时段 | 内容 |
|---|---|
| 1 min | 开场 |
| 7 min | **现场 Demo**：让 Agent 生成贪吃蛇 + 修改 + 权限拦截 |
| 2 min | 1 · ReAct 循环 |
| 2 min | 2 · OpenAI Function Calling |
| 2 min | 3 · 项目记忆（AGENTS.md） |
| 2 min | 4 · Skill 渐进披露 |
| 2 min | 5 · TodoList 外部工作记忆 |
| 4 min | 6 · **Cache-aware Design** ⭐ |
| 2 min | 7 · 权限系统 |
| 3 min | 8 · 延伸话题速览（Subagent / 压缩 / MCP / Hooks） |
| 1 min | 9 · 三个原理的统一思想 |
| 5 min | Q&A |

---

## 0. 为什么做这个项目

### 起点

- 想搞清楚 Claude Code / Codex / Cursor 这些 Coding Agent 内部是怎么工作的
- LangChain / Semantic Kernel 这类框架封装太深，看不到本质
- 决定从零搭一个

### 约束

- Java 17，零 AI / Agent 框架依赖（只有 Gson + JDK HttpClient）
- 全项目 < 1500 行 Java
- "代码即文档"——每个类只做一件事，注释讲原理

### 现状

- 25 个 Java 文件，覆盖 8 个核心能力
- **已实现**：ReAct / Function Calling / AGENTS.md / Skill / TodoList / Edit 工具 / Cache-aware 注入 / 权限系统
- **未实现但写了知识文档**：Subagent / Context 压缩 / MCP / Plan Mode

---

## 1. ReAct 循环（Reasoning + Acting）

### 核心思想

LLM 不是一次给答案，而是循环：

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

→ 长对话里 token 反复付费 → 后面 §6 Cache-aware Design 怎么应对

---

## 2. OpenAI Function Calling

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

- 不是让 LLM 用自然语言"说"想调用什么——LLM 直接返回**结构化 JSON**
- 工具的 schema 不是 prompt 描述，是协议层的字段
- 所有 OpenAI 兼容 API（DeepSeek / Moonshot / 通义...）都支持

### 本项目实现

- Tool → Schema 自动转换：`agent/tools/ToolDefinition.java`（30 行）
- 工具注册：`agent/tools/ToolRegistry.java`
- HTTP 客户端：`agent/llm/LLMClient.java`（90 行，纯 JDK HttpClient）

---

## 3. 项目记忆：AGENTS.md

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

---

## 4. Skill：渐进披露（Progressive Disclosure）

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

---

## 5. 外部工作记忆：TodoList

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
| `agent/tools/TodoWriteTool.java` | 工具实现（含三项校验：非空/status 合法/最多一个 in_progress） |

### 真正改变的不是"功能多了一个"

是 LLM 的**规划行为**被强化——看到自己的清单就不会跳步。

---

## 6. Cache-aware Design ⭐（最重要的设计原理）

### 问题：踩过的坑

最初实现 TodoList 时，把"当前任务清单"拼到 `SystemPromptBuilder.build()` 末尾——看起来超级自然。

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

时间戳 / session id / 用户信息 / 外部状态 / 任何随轮次变化的内容。

> 详细原理见 `docs/prompt-cache-aware-design.md`（4500 字深讲）

---

## 7. 权限系统：从工具层画安全边界

### 问题

`execute_command` 直接 `/bin/sh -c`，没有任何拦截 → LLM 一旦想 `rm -rf /` 就炸了（真实事故案例存在）

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

- **不靠 prompt 劝**——而是 PreToolUse 闸门，工具执行前拦截
- 命中 deny / 用户拒绝时，把"被拒绝"的事实回写给 LLM → LLM 自己决定下一步（换方式 / 告知用户 / 放弃）

### 本项目实现

| 文件 | 职责 |
|---|---|
| `agent/permission/Decision.java` | 三态枚举 |
| `agent/permission/PermissionPolicy.java` | 加载规则 + 子串匹配 |
| `agent/permission/PermissionGate.java` | 闸门 + ASK 交互 |
| 集成点 | `agent/core/ReActLoop.java:113` |

### 比 OS 沙箱差什么

权限系统拦不住 LLM 的"绕路攻击"（找另一条命令绕过）。

真正的护城河是 OS 级沙箱（macOS Seatbelt / Linux Landlock）—— 但那超出学习项目范围。

---

## 8. 延伸话题（每个 30-45 秒口述）

### 8.1 Subagent

- 上下文隔离 + 委派-汇总
- 主 Agent 派子 Agent 干脏活，子 Agent 独立 context、只回总结
- 解决 Context Dilution（长 session context 被撑爆）
- Anthropic 金句：*"Share memory by communicating"*（来自 Go 社区）
- 详见：`docs/subagent-primer.md`

### 8.2 Context 压缩 / Auto-Compact

- Claude Code 在 95% context 时触发，把历史压成 summary
- **Turn-safe 边界**很关键：绝不能切断 tool_call 和 tool_result 的对应关系
- 本项目暂未实现

### 8.3 MCP（Model Context Protocol）

- 2024-11 Anthropic 提出，2025-12 捐给 Linux 基金会
- 一句话理解：**USB-C for AI tools**——一个 MCP Server 实现一次，所有 Agent 都能用
- OpenAI / Cursor / Google 全部接入了
- 2026 年想理解 Agent 生态，绕不开 MCP
- 本项目暂未实现

### 8.4 Hooks

- Claude Code 的 PreToolUse / PostToolUse 钩子，用外部进程做硬拦截
- 典型用途：自动 lint / 自动 git commit / 敏感操作 Slack 审批
- 和权限系统重叠但**更可定制**——前者内建、后者外置

### 完整路线图

`docs/2026-coding-agent-gap-analysis.md` 有 16 项能力的 P0/P1/P2 路线表。

---

## 9. 三个原理的统一思想

| 能力 | 边界画在哪 |
|---|---|
| 权限系统 | 工具调用层（PreToolUse 拦截） |
| Cache-aware 注入 | 消息结构层（动态状态走 tool_result） |
| Subagent 防递归 | 工具集合层（子 Agent 不含 SubagentTool） |

**统一心智模型**：

> **从架构层画边界，不靠 prompt 劝模型。**
>
> Prompt 是描述意图的，可以被 LLM 违反；架构是保证边界的，LLM 没法违反。

---

## 10. 总结

**今天分享请记住三件事**：

1. **Agent 的核心是 ReAct + Function Calling**——14 个 Java 文件就够了
2. **上下文工程是 Coding Agent 的灵魂**——AGENTS.md / Skill / TodoList / Cache-aware 都是同一类东西
3. **架构边界 > prompt 边界**——权限、防递归、防 cache 失效都是工具层 / 消息结构层的事

**继续学习的入口**：

- 项目源码：`src/main/java/agent/`（25 个文件，每个都短小）
- 调研全景：`docs/2026-coding-agent-gap-analysis.md`
- Cache 原理深讲：`docs/prompt-cache-aware-design.md`
- Subagent 知识：`docs/subagent-primer.md`

---

## 11. 附录：自己做 Agent 时的陷阱清单

> 30 分钟讲不完，这节留作**会后材料** / 你将来自己动手时的 Checklist。
> 这些坑我自己踩过，或者在调研 Claude Code / Codex 源码时看到别人踩过。

### 11.1 上下文工程（和 §6 一脉相承）

- **动态内容不能进 system prompt**（已详讲）
- **JSON Schema 字段顺序必须稳定**：Gson 的 `JsonObject` 序列化按**插入顺序**。千万别用 `HashMap` 构造 schema——两次生成的字节序列会不同，整个 `tools` 字段 cache 失效
- **`tools` 数组顺序必须稳定**：本项目用 `LinkedHashMap` 注册工具，顺序固定。**动态开关工具**（根据用户身份）会让整个缓存爆掉——请三思
- **任何每轮变化的值都走 `<system-reminder>`**：时间戳、session id、用户名字、外部系统健康状态、当前 git branch、当前工作目录

### 11.2 工具设计

- **description 写"何时使用"，不只是"是什么"**：`"Read a file"` 不如 `"Read a file when you need its contents. Prefer edit_file over reading and rewriting."`
- **错误消息是给 LLM 读的，不是给人读的**：
  - ✓ `"文件不存在：/tmp/x。建议先用 list_files 确认路径"`
  - ✗ `"java.nio.file.NoSuchFileException at ReadFileTool.java:52"`
- **参数防御性校验**：即使 JSON Schema 标了 `required: ["path"]`，代码里仍要处理 path 缺失、为 null、为空串。**LLM 不保证遵守 schema**
- **命名动词开头 + 明确**：`read_file` / `write_file` / `edit_file`——比 `file_reader` / `file_manager` 好
- **相似工具的边界要清晰**：避免 `write_file` 和 `edit_file` 功能重叠不清——description 里要写明"创建新文件用 write_file，修改已存在文件用 edit_file"

### 11.3 循环控制

- **`max_iterations` 必须有上限**：没有就会无限跑。常见的 ping-pong 场景：LLM 在两个工具之间横跳，每次都觉得"下一次会更好"
- **Temperature 在 Agent 场景要低**（0.0 ~ 0.2）：Agent 不需要创造力，需要**可复现**。很多人默认 0.7 结果 Agent 行为抖动
- **HTTP timeout 必须设**：connect timeout（~10s）和 read timeout（~60s+）分离。LLM API 偶尔卡 30s 以上
- **区分 `context_window` 和 `max_tokens`**：前者是输入上限，后者是输出上限。搞混了会看到奇怪的 400 错误

### 11.4 调试与可观察性

- **完整日志是救命稻草**：每次请求 / 响应都 pretty-print JSON 到文件——90% 的 Agent bug 靠对比两次请求的 prompt diff 就能定位
- **"LLM 为什么这样做"的答案**：80% 在 prompt 里——包括 system、AGENTS.md、tool description、history 的累积效应
- **每个工具调用都要有视觉反馈**：开发时你自己 debug 也需要——没反馈的循环调试起来很痛
- **重要决策点打"快照"**：cache 命中率、token 消耗、单轮耗时——分享 / 问题定位时都用得上

### 11.5 模型兼容性

- **不同模型对 Function Calling 遵守度不同**：Claude 最严格（`tool_call_id` 对齐要求高）、GPT-4o 居中、DeepSeek / Qwen 偶尔跳过必填字段或返回奇怪的 `arguments` 格式
- **换模型必须重跑"工具覆盖测试"**：不能假设"OpenAI API 兼容 = 行为兼容"。尤其是 `tool_calls` 数组和 `arguments` 字段
- **DeepSeek-chat 和 DeepSeek-coder 对工具的遵守度不一样**：实测有差异，切换前必须对比
- **`arguments` 字段是字符串不是对象**：OpenAI 协议要求 `arguments` 是 JSON 字符串——需要 `JsonParser.parseString(argsString)` 二次解析。新手常栽这里

### 11.6 心智模型（最容易忽视的那类问题）

- **Agent ≠ 传统 API**：一次用户输入可能触发 N 轮 LLM 调用、M 个工具执行。监控、计费、延迟度量方法**完全不同**
- **不要迷信 Function Calling 的"结构化"**：LLM 仍会传错参数、跳过字段。**代码层永远要有最后一道防线**（校验 + 容错）
- **Prompt 描述意图，架构保证边界**：今天分享的核心金句——能用架构解决的问题，别用 prompt 去劝模型
- **大任务先让 LLM 拆解再做**：直接"帮我做 X"容易走偏；"先给我一个 todo 计划，用 todo_write 记录"往往更可靠
- **Agent 不是越"自主"越好**：Claude Code 的 permission 模式、Codex 的"explicit 触发 subagent"都在约束自主度——过度自主 = 过度不可控

### 11.7 一条通用的心法

> **开发时把你自己当成 LLM**：对着当前 context（system + 历史），你知道下一步该做什么吗？
>
> - 看不清 → 是 prompt 没写好
> - 看得清但 LLM 不做 → 是工具定义没写好
> - 做错了方向 → 通常是工具集里缺了一个关键的
>
> 大多数 Agent 调试问题都能归到这三类之一。

---

## Q&A

[投屏停留]

---

## 致谢与参考

- Anthropic — Claude Code Docs / Multi-Agent Research System
- OpenAI — Codex CLI Docs
- Cursor — Background Agents 设计
- AGENTS.md / MCP — Linux Foundation Agentic AI Foundation
- 完整参考链接见 `docs/2026-coding-agent-gap-analysis.md` 第 7 节
