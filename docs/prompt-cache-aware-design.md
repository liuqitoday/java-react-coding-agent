# Cache-Aware Context Engineering：为什么动态状态不能放进 System Prompt

> 撰写日期：2026-04-19
> 触发情景：本项目实现 TodoList 工具时，最初把"当前任务清单"拼在 `SystemPromptBuilder.build()` 末尾，走查时发现这会击穿 Prompt Cache 命中率。

---

## TL;DR

- Prompt Caching 是**字节级前缀匹配**：system message 里一旦有变，它及其后所有消息的 KV-Cache 全部失效
- 因此：TodoList、已激活 Skill 等"每轮都可能变化的状态"**绝不能**直接拼在 system prompt
- 正确做法：把动态状态作为 `<system-reminder>` 附加到**每轮最后一条 tool_result** 的 content 末尾
- 这是 Claude Code 的设计——背后是"KV-Cache 的物理机制 + 前缀匹配"这个不可绕的约束

---

## 1. 背景：一次现实踩坑

本项目实现 TodoList 时，第一版把"当前任务清单"直接拼到 `SystemPromptBuilder.build()` 的末尾。看起来很自然：

- 每轮 LLM 调用前，Agent 都会重新 build 一次 system prompt
- todo 状态跟 system 同步维护，LLM 持续看到最新计划
- 代码简单：`SystemPromptBuilder` 持有 `TodoList` 引用，build 时拼一段

**但这个设计在真实 API 环境下会让 Prompt Cache 命中率雪崩**。而且这个 bug 在所有"状态更新"的场景都会复现——TodoList 只是一个切面，已激活 Skill 状态、时间戳、session_id 等等都是同样的坑。

---

## 2. Prompt Cache 的实际工作方式

### 2.1 接口层表现

所有主流 LLM API 的 Prompt Caching 都是 **prefix matching**（前缀匹配）：

| 提供方 | 机制 | 最小触发 | 命中折扣 |
|---|---|---|---|
| OpenAI | 自动 prefix caching，按前 256 token 路由，128 token 增量对齐 | ≥1024 token | cache read 按输入的 ~50% 计费 |
| Anthropic | `cache_control` 显式断点，或自动管理 | Sonnet 1024 / Haiku 4096 token | cache read 按输入的 10% 计费 |
| DeepSeek | 自动 prefix caching | 起步较低 | cache hit ~10-20% 价格 |

不同提供方的实现细节不同，但**前缀匹配**这个核心是一致的。

### 2.2 底层原理：KV-Cache

Transformer 推理时，每一层的 Self-Attention 都会为输入序列计算出 Key 和 Value 张量。这些张量一旦算出来就**不随后续 token 改变**（当前位置的 KV 只依赖于前缀）。于是：

- 同一个前缀的 KV 张量可以跨请求复用
- 服务器把 KV-Cache 按 prefix 内容做 hash 索引，下次匹配到就直接加载，跳过重新计算
- **只要 prefix 的某一个 token 变了，从那一位开始往后的所有层、所有 KV 张量都必须重算**

所以 "Prompt Cache 命中" 等价于 "前缀字节完全一致，从 KV-Cache 拿现成结果"。

> **关键金句**：*Prompt Cache 不是 LLM 的某个"特性"，是 KV-Cache 这一物理事实的延伸。理解了推理时发生什么，后面所有 cache-aware 设计都是顺理成章的。*

### 2.3 请求结构下的缓存边界

一个 Chat Completions 请求发给服务端后，会被拼接成：

```
[tools 定义数组] + [system message] + [message 1] + [message 2] + ... + [message N]
```

这一整段从头开始按字节匹配。所以：

- 如果 tools 的字节变了 → 从 tools 开始往后全部 cache miss
- 如果 system message 的字节变了 → 从 system 开始往后全部 cache miss
- 如果 message 3 变了 → 从 message 3 开始往后 cache miss；前 2 条仍然命中

**最糟糕的场景：system message 末尾每次变。** 这意味着几乎整个请求都得重新计算——即使你 95% 的内容没变。

---

## 3. 我最初代码的问题

`SystemPromptBuilder.build()` 的拼接顺序：

```
[basePrompt]                    ← 完全静态
+ [项目上下文 AGENTS.md]        ← 启动加载，Session 内静态
+ [Skill 目录]                  ← 启动扫描，Session 内静态
+ [已激活 Skill 状态]           ← 每次 activate_skill 后变化 ✗
+ [TodoList 清单]               ← 每次 todo_write 后变化 ✗
```

每次调用 `todo_write` 或 `activate_skill` 后，下一轮 LLM 请求的 system message 尾部与上一轮不再一致：

1. system message 哈希变 → 整个 system message 从头 cache miss
2. 后面所有历史消息（可能几十上百轮对话）在这次请求里都**无法命中缓存**
3. 即使前 99% 内容完全没变，服务端也必须重新计算 KV

对话越长、状态更新越频繁，损失越严重。一个 1 小时的 coding session 可能浪费掉 70%+ 本可命中的缓存 token。

**Anthropic 官方文档原话**：

> *"If you need part of your prompt to stay dynamic, move that dynamic content into a later user message instead of appending it after a cached block in the first system message."*
>
> *"Never inject dynamic variables (timestamps, session IDs) into cached blocks."*

---

## 4. 正确姿势：动态状态走 tool_result

### 4.1 Claude Code 的解法：`<system-reminder>` 注入

观察 Claude Code 的实际 session 日志，能看到它在某些 tool_result 消息的 content 末尾追加 `<system-reminder>` 块：

```xml
<tool_result for="some_tool">
正常的工具输出...
<system-reminder>
当前 todo 清单：
[x] Create index.html
[~] Adding CSS styling
[ ] Write game loop
</system-reminder>
</tool_result>
```

为什么这样能解决问题？

```
[system message]                       ← 永远静态 → cache 长期命中
[user: 第 1 条]                        ← 历史固定 → cache 命中
[assistant(tool_calls)]                ← 历史固定 → cache 命中
[tool_result A]                        ← 历史固定 → cache 命中
[tool_result B + <system-reminder>]    ← 历史固定后也继续命中
[user: 第 2 条]
[assistant(tool_calls)]                ← 这一轮新内容，cache miss 不可避免
[tool_result A]
[tool_result B + <system-reminder>]    ← 这一轮新内容
```

动态状态被注入到"本轮新增的 tool_result 末尾"。这一条消息本来就是新的（cache miss 不可避免），在它末尾追加动态内容**零额外成本**。而前面所有历史消息因为字节没变，在下一轮仍然 cache hit。

### 4.2 为什么只在"本轮最后一条" tool_result 附加？

同一轮里可能有多个 tool_call（LLM 并行调用多个工具）。如果每条 tool_result 都附加一份完整 reminder，LLM 在同一个位置连续看到 3 份几乎一样的 "当前 todo 清单"，容易混淆；而且这 3 条 reminder 会被永久写入历史，后续请求每次都重传，浪费 token。

**只在本轮最后一条附加**：LLM 按序处理消息，最后看到的就是当前最新状态——信息密度最高，浪费最少。

---

## 5. 本项目的实现

### 5.1 模块分工

| 组件 | 职责 | 内容是否随时间变化 |
|---|---|:-:|
| `agent.core.SystemPromptBuilder` | 组装 Session 内静态内容：basePrompt + AGENTS.md + Skill 目录 | ✗ |
| `agent.core.StateReminder` | 生成动态状态的 `<system-reminder>` 块：TodoList + 已激活 Skill | ✓ |
| `agent.core.ReActLoop` | 执行工具时，本轮最后一条 tool_result 末尾追加 reminder | — |

### 5.2 关键代码点

- `StateReminder.buildReminder()` — 拼接所有动态状态为 `<system-reminder>...</system-reminder>`；所有状态为空时返回空串（不追加也不打标签）
- `ReActLoop.maybeAppendReminder()` — 只对本轮索引 `i == total - 1` 的 tool_result 追加
- `SystemPromptBuilder.build()` — **故意不包含**任何动态状态，保持 Session 内 build() 结果恒定

### 5.3 验证方式

1. 启动 Agent，输入一个会触发 `todo_write` 和几个工具调用的任务（比如 "创建 index.html 和 style.css 两个空文件，用 todo 追踪"）
2. 打开 `logs/llm_*.log`，对比两次 LLM 请求的 `messages[0]`（system message）
3. 预期：**两次的 system message 字节完全相同**；TodoList 的变化只出现在最后一条 tool_result 的 content 尾部

---

## 6. 可推广的设计原则

### 6.1 内容分层

| 内容类型 | 放哪里 |
|---|---|
| Agent 角色、基础指令 | system prompt 开头 |
| 项目 CLAUDE.md / AGENTS.md | system prompt（启动时固定） |
| 可用工具/Skill 目录（名称+描述） | system prompt（启动时固定） |
| 当前任务进度（TodoList） | tool_result 末尾 `<system-reminder>` |
| 已激活 Skill / 已加载上下文 | tool_result 末尾 `<system-reminder>` |
| 外部系统状态（DB 健康、时钟） | tool_result 末尾 `<system-reminder>` |
| 用户新输入 | 新的 user message（天然如此） |
| RAG 检索结果 | 如想 cache，用独立 system 块 + 显式 `cache_control` |

### 6.2 一条简单规则

> **"可能每轮变化的任何内容，都不能放进 system message。"**

包括但不限于：
- 时间戳、日期
- session id、request id、user id
- 当前工作目录（如果支持动态切换）
- 任务进度、子 Agent 状态
- Feature flag / 运行时开关

### 6.3 更精细的分层：多级缓存断点

Anthropic 最多支持 4 个 `cache_control` 断点。可以按变化频率分层：

```
[完全静态：basePrompt]          ← 断点 1（跨月不变）
[每周一变：项目 CLAUDE.md]     ← 断点 2
[每次启动变：tool 定义]        ← 断点 3
[动态内容]                      ← 不打断点（走 tool_result 注入）
```

但对学习项目来说过度设计。**单一 system 块 + `<system-reminder>` 注入**已能拿到 80%-90% 的收益。

---

## 7. 容易忽视的三个坑

### 7.1 工具定义顺序

不仅 system message，**`tools` 字段数组本身**也是前缀的一部分。

- 如果 `ToolRegistry.toJsonArray()` 每次生成的顺序不确定（比如用 `HashMap` 迭代），cache 失效
- 本项目用 `LinkedHashMap` + 手工构造 `JsonObject`，两次调用生成的字节序列完全一致——无意中避开了这个坑

动态注册/注销工具（比如根据用户身份开放不同工具）需要谨慎：只要工具集变了，整个缓存就失效。

### 7.2 JSON 字段顺序

Gson 的 `JsonObject` 内部用 `LinkedTreeMap`，插入顺序即序列化顺序。所以**手工 `addProperty` 的顺序必须一致**——同一个 schema 两次构造时如果字段顺序不同（比如一次先加 `description` 后加 `type`，另一次反过来），字节就不一样。

本项目所有工具的 `parameterSchema()` 方法都是固定顺序构造的，安全。但如果将来从 POJO 自动生成，要小心顺序一致性。

### 7.3 注入式污染

常见的反面模式：

```java
// ✗ 错误：往 system 里注入时间戳
systemPrompt += "\n当前时间：" + Instant.now();

// ✗ 错误：往 system 里注入 session id
systemPrompt += "\nSession ID: " + sessionId;

// ✗ 错误：往 system 里注入用户信息
systemPrompt += "\n用户: " + user.getName() + "（ID " + user.getId() + ")";
```

这些是教科书级的 cache killer。正确做法：用户信息在首条 user message 或单独的 tool_result 注入；时间戳通常根本不需要（LLM 不靠它理解任务），如必须则放动态位置。

---

## 8. 演示要点（内部分享专用）

- **反例开场**：切回 bug 版代码（把 TodoList 拼在 system 末尾），启动 Agent 跑一轮，打开 LLM 后台的 "cached tokens" / "cache hit rate" 指标；然后切到修复版本，对比指标——效果非常直观
- **核心金句**：*"Prompt Cache 不是 LLM 的特性，是 KV-Cache 这一硬件事实的延伸"*
- **陷阱清单作为结尾 slide**：JSON 字段顺序 / 工具顺序 / timestamp / session_id / 用户信息注入
- **延伸讨论**：为什么 Anthropic 允许 4 个 `cache_control` 断点？提示：对应的是不同更新频率的内容层次

---

## 9. 延伸阅读

### 官方文档

- [OpenAI — Prompt Caching Guide](https://developers.openai.com/api/docs/guides/prompt-caching)
- [OpenAI Cookbook — Prompt Caching 201](https://developers.openai.com/cookbook/examples/prompt_caching_201)
- [Anthropic — Prompt Caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)

### 实际案例

- OpenAI Codex CLI 源码 `codex-rs/core/src/codex.rs`：官方工程团队如何保证 prefix 稳定
- Spring AI — [Prompt Caching Support with Anthropic Claude](https://spring.io/blog/2025/10/27/spring-ai-anthropic-prompt-caching-blog/)
- PromptHub — [Prompt Caching with OpenAI, Anthropic, and Google Models](https://www.prompthub.us/blog/prompt-caching-with-openai-anthropic-and-google-models)
