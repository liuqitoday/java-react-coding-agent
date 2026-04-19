# 30 分钟内部分享 · 演示脚本（私下背用）

> 配套投屏：`docs/ai-agent-sharing.md`
> 总时长：30 分钟（含 Q&A 5 分钟）
> 核心 Demo：贪吃蛇生成 → 修改 → 权限拦截

---

## 现场前 5 分钟必做清单

```
[ ] cd 到项目目录（D:/... 或 ~/...）
[ ] cp permissions.json.example permissions.json
[ ] rm -f logs/*.log（清空旧日志）
[ ] 准备一个空 demo 目录（让贪吃蛇文件落在干净位置）
    例如：mkdir -p ~/Desktop/snake-demo && cd ~/Desktop/snake-demo
[ ] 测试 LLM API 是否正常（先发个 "hi" 验证 key）

[ ] 打开两个终端窗口
    - 终端 A：跑 agent（mvn -q exec:java -f /path/to/pom.xml）
    - 终端 B：tail -f logs/llm_*.log（cache-aware 段切过去看）

[ ] 打开 IDE，预先打开这些 Tab：
    - ReActLoop.java
    - SystemPromptBuilder.java
    - StateReminder.java
    - TodoWriteTool.java
    - EditFileTool.java
    - PermissionPolicy.java
    - permissions.json.example

[ ] 投屏切到 ai-agent-sharing.md，停在标题页

[ ] 准备好 demo 输入（写在便签上备查）：
    1. 帮我创建一个贪吃蛇游戏。先生成 index.html，包含基础结构、
       蛇的渲染和键盘控制。用 todo_write 拆解你的步骤。
    2. 把蛇的颜色改成蓝色，把移动速度提高 30%
    3. 帮我清理一下 demo 过程中产生的 .log 文件，直接 rm -rf
```

---

## 时间表（总 30 分钟）

| 时段 | 内容 | 投屏位置 |
|---|---|---|
| 00:00 - 01:00 | 开场 | 标题页 |
| 01:00 - 04:00 | **Demo 1**：生成贪吃蛇 | 终端 A |
| 04:00 - 06:00 | **Demo 2**：修改颜色 + 速度 | 终端 A |
| 06:00 - 07:30 | **Demo 3**：权限拦截 | 终端 A |
| 07:30 - 08:00 | 过渡 | 切回投屏 §1 |
| 08:00 - 10:00 | §1 ReAct 循环 | §1 |
| 10:00 - 12:00 | §2 Function Calling | §2 |
| 12:00 - 14:00 | §3-4 项目记忆 + Skill | §3 → §4 |
| 14:00 - 15:30 | §5 TodoList | §5 |
| 15:30 - 19:30 | §6 **Cache-aware Design** ⭐ | §6（含切终端 B） |
| 19:30 - 21:00 | §7 权限系统 | §7 |
| 21:00 - 24:00 | §8 延伸话题 | §8 |
| 24:00 - 25:00 | §9 三个原理统一 | §9 |
| 25:00 - 30:00 | Q&A | §10 → Q&A |

---

## 00:00 - 01:00 · 开场

[动作] 投屏停在标题页

> "今天分享一个我业余做的小项目——一个完全用 Java 17 写的 Coding Agent，零 AI 框架依赖。
>
> 起因是想搞清楚 Claude Code、Codex、Cursor 这些工具到底是怎么工作的。市面上的框架封装太深，看不到原理，所以从零搭了一个。
>
> 大概 25 个 Java 文件，1500 行代码，但已经能跑完整的 ReAct 循环。今天 30 分钟想跟大家讲清楚里面几个最有意思的设计原理。
>
> 我先现场让它跑一个贪吃蛇给大家看，然后我们再回来拆原理。"

[动作] 切到终端 A

---

## 01:00 - 04:00 · Demo 1：生成贪吃蛇

[动作] 终端 A 启动 Agent

> "Agent 启动了——加载了项目上下文文件、注册了 8 个工具、加载了 9 条权限规则。"

[动作] 输入：

```
帮我创建一个贪吃蛇游戏。先生成 index.html，包含基础结构、
蛇的渲染和键盘控制。用 todo_write 拆解你的步骤。
```

> "看，第一步它先调 todo_write 把任务拆成 4 个步骤。" [手指 todo 输出]
>
> "下面它开始执行——write_file 创建 index.html，注意 todo 的状态在变化。"

**预期 Agent 行为序列**：
1. `todo_write`（4-5 个 pending）
2. `todo_write`（标记第 1 个 in_progress）
3. `write_file`（index.html）
4. `todo_write`（1 completed → 2 in_progress）
5. ...直到全部 completed

> "看到没，每一步它都在更新清单。这个 todo 不是 UI 装饰——是 LLM 自己的工作记忆。每轮 LLM 调用前都会看到当前 todo 状态，所以它不会忘自己要干什么。"

**🚨 备案 - 如果 Agent 走偏了**

| 情况 | 应对台词 |
|---|---|
| 没用 todo | "我们手动让它用——重新跑" 或 "OK，它觉得这个任务太简单不用 todo，我们看下一个" |
| 卡在某个工具 | Ctrl+C，"演示日常翻车，正好说明 LLM 的不确定性，看下一个" |
| 写错代码 | "代码不重要，我们看的是 Agent 行为流程" |
| 输出乱码 | 重启 agent，"网络抖动，我们继续" |

---

## 04:00 - 06:00 · Demo 2：修改

[动作] Agent 跑完后，输入：

```
把蛇的颜色改成蓝色，把移动速度提高 30%
```

> "现在它要改两个地方。注意它怎么做的——**不是 write_file 把整个文件重写**，而是 edit_file 精准替换那一段。"

**预期 Agent 行为**：
1. `read_file`（先读现有文件）
2. `edit_file`（改颜色，精准替换 old_string → new_string）
3. `edit_file`（改速度）

> "edit_file 的设计要点：old_string 必须**精确匹配**文件内容，且默认在文件中**唯一出现**。这是防止误改其他相似代码段的核心机制。Claude Code 用的是同样的模式。"

> "用 edit 而不是 write 的好处：① token 成本低，只传变化部分；② 风险小，LLM 不会因为复述整个文件漏掉无关代码。"

---

## 06:00 - 07:30 · Demo 3：权限拦截

[动作] 输入：

```
帮我清理一下 demo 过程中产生的 .log 文件，直接 rm -rf
```

> "现在我故意让它做一件危险的事。"

**预期**：
- Agent 调 `execute_command("rm -rf *.log")` 或类似
- 命中 `permissions.json` 里 `execute_command:rm -rf` 的 deny 规则
- 终端打印 `[拒绝] 权限策略拒绝了此工具调用...`

> "看，被拦了。这**不是** prompt 让 LLM 别做坏事——是工具执行前的硬拦截。LLM 现在收到了拒绝消息，它会自己决定下一步。"

[等待 Agent 反应]

**Agent 大概率会**：
- 改用 `find . -name "*.log" -delete`（也可能命中 ASK）
- 或告诉用户"被权限策略阻止了，建议你手动删除"

> "这就是权限系统的'**闭环反馈**'——LLM 不是被打断，是收到环境的反馈后继续推理。这是个非常重要的设计。"

---

## 07:30 - 08:00 · Demo 收尾 + 过渡

[动作] 切回投屏，知识文档 §1

> "OK，刚才大家看到的：todo 拆解、edit 精准修改、权限拦截——这些是面，下面我们讲背后的原理。
>
> 整个项目的代码会在分享后发到群里，大家有兴趣可以 clone 下来读源码——我承诺所有原理都能在 100 行 Java 内找到对应实现。"

---

## 08:00 - 10:00 · §1 ReAct 循环

[投屏：§1]

> "ReAct 是 Reasoning + Acting 的缩写。LLM 不是一次给答案，而是多轮循环：思考 → 调工具 → 看结果 → 继续。
>
> 看屏幕这个例子：用户问'读 pom.xml'，LLM 先想'我应该读文件'，然后调 read_file 工具，看到内容后再回答。
>
> 我们项目里 ReActLoop 这个类大概 130 行，是整个 Agent 的心脏——所有逻辑都围绕'循环到 LLM 不再要求工具调用'这件事。
>
> 一个关键 trade-off：每一轮 LLM 调用都要把所有历史消息重发一遍，因为 LLM 没有持久记忆。所以长对话里 token 会反复付费——这点后面 Cache-aware 部分会讲怎么对付。"

[投屏切 §2]

---

## 10:00 - 12:00 · §2 Function Calling

[投屏：§2]

> "Agent 怎么调用工具？不是让 LLM 用自然语言说'我想调 read_file'——而是用 OpenAI 设计的 Function Calling 协议。
>
> 我们在请求里塞 tools 数组，每个工具用 JSON Schema 描述。LLM 直接返回结构化的 tool_calls 字段，里面有工具名和参数。
>
> 这个协议被 OpenAI 提出，但**所有兼容 API**——DeepSeek、Moonshot、通义——都支持。所以这个项目可以无缝换底层模型。
>
> 一个有意思的点：我们的 Tool 接口只有 4 个方法（name / description / parameterSchema / execute），但通过 ToolDefinition 自动转成 OpenAI 协议。**20 行代码就能加一个新工具**。"

---

## 12:00 - 14:00 · §3-4 项目记忆 + Skill

[投屏：§3]

> "Agent 怎么知道当前项目的约定？AGENTS.md 标准——这是 Linux 基金会下的 Agentic AI Foundation 在维护的开放规范，Codex 和 Claude Code 都遵循。
>
> 启动时从 git root 一直扫到当前目录，所有 AGENTS.md 文件按层级拼接进 system prompt。"

[投屏切 §4]

> "但这有个问题：如果想给 Agent 加几十个 skill——PR 审查、安全扫描、代码生成——全塞进 system prompt 每次都要发几万 token。
>
> 解法是 **Skill 渐进披露**：启动时只读 frontmatter，大约 100 token 的元数据，把目录放 prompt。LLM 判断需要某个 skill 时再调 activate_skill 加载完整内容。这是 Claude Code 在用的模式。
>
> 类比一下：像 IDE 的按需加载插件——不打开就不消耗。"

---

## 14:00 - 15:30 · §5 TodoList

[投屏：§5]

> "刚才 demo 里你们看到的 TodoList——它真正解决的是 LLM 在多步任务里**容易忘事**的问题。
>
> 设计上是单工具全量替换，**不是** create / update / delete 多个工具。Claude Code 现在也是这个设计。好处：演示时听众一眼能看到完整状态，LLM 不需要记 id。
>
> 但 TodoList 真正改变的不是'多了个功能'——是 LLM 的**规划行为**被强化了。每轮看到自己的清单，它就不会跳步、不会忘事。
>
> 这是一种典型的'**外部工作记忆**'设计——让 Agent 不依赖自己的 context 记忆，而是依赖外部状态。"

---

## 15:30 - 19:30 · §6 Cache-aware Design ⭐

> "这一节是今天我**最想讲清楚**的，因为它体现了 Agent 设计的根本原则。"

[投屏：§6 第一页]

> "故事是这样：我最初实现 TodoList 时，把当前任务清单拼在 SystemPromptBuilder.build() 末尾。看起来超级自然——LLM 每轮都能看到最新状态。
>
> 但走查的时候发现这个设计会让 **Prompt Cache 命中率雪崩**。"

[投屏：缓存命中折扣表]

> "Prompt Cache 是什么？所有主流 LLM API 都支持，OpenAI、DeepSeek 都是按 prefix 字节匹配缓存。命中的 token 收 10% 价格——5 倍以上的成本差距。"

[切到终端 B / 或继续投屏]

> "如果 system 末尾每次变化——TodoList 一更新就变——下一轮请求的 system 哈希就变，**整个 system 之后所有的历史消息全部 cache miss**。1 小时对话可能浪费 70% 本可命中的缓存。
>
> 底层原因是 **KV-Cache** 的物理机制——Transformer 推理时按层缓存 KV 张量，按 prefix 哈希索引。前缀 1 个字节变了，从那位往后全部要重算。"

[投屏：§6 第二页 - 解法图]

> "所以正确做法是 Claude Code 的设计：动态状态打包成 `<system-reminder>` 块，**追加到本轮最后一条 tool_result 的 content 末尾**。tool_result 本来就是每轮新增的，在它末尾追加内容**零额外成本**——而 system 永远静态。
>
> 我们项目里有 StateReminder 这个类专门干这件事，ReActLoop 第 113 行有注入点。"

[切到 IDE 看 StateReminder.java 几秒]

> "推广来说：**任何'可能每轮变化'的内容都不能放 system**——时间戳、session id、用户信息、外部状态。这是一条铁律。
>
> 这一节我专门写了文档——`docs/prompt-cache-aware-design.md`，4500 字详讲。感兴趣的同事会后可以读。"

---

## 19:30 - 21:00 · §7 权限系统

[投屏：§7]

> "刚才 demo 里 rm -rf 被拦的那一幕，背后是一个**三态决策系统**：ALLOW / DENY / ASK。
>
> 配置在 permissions.json，规则格式很简单——`tool_name:子串`，对参数 JSON 做子串匹配。
>
> 关键设计：**这不是在 prompt 里劝 LLM 别做坏事**——是 PreToolUse 闸门，在工具真正执行前拦截。命中 deny 直接拒绝；命中 ask 弹给用户；其他静默放行。
>
> 拒绝时把'被拒绝'的事实**写回给 LLM**，它自己决定换方式还是放弃。这是闭环反馈——Agent 不是被打断，是收到环境信号后继续推理。
>
> 一个诚实声明：权限系统**不是真正的护城河**——LLM 可以绕路，'不让 rm -rf 我就 find -delete'。真正的护城河是 OS 级沙箱——macOS Seatbelt、Linux Landlock。但那超出学习项目范围。"

---

## 21:00 - 24:00 · §8 延伸话题

[投屏：§8]

> "下面四个话题我**没在项目里实现**，但每个都值得至少 30 秒了解。"

### Subagent（30 秒）

> "上下文隔离——主 Agent 派一个临时的、干净 context 的子 Agent 去干脏活，只回一段总结。解决长任务里 context 被读文件结果撑爆的问题。
>
> Anthropic 那句金句：'Share memory by communicating'，原话来自 Go 社区。
>
> 我写了 `docs/subagent-primer.md` 详细讲。"

### Context 压缩（30 秒）

> "Auto-Compact——长 session 自动用 LLM 把历史压成 summary，留最近 K 轮，前面的扔掉。Claude Code 在 95% context 触发。
>
> Turn-safe 边界很关键——绝不能切断 tool_call 和 tool_result 的对应关系。"

### MCP（45 秒）

> "Model Context Protocol——2024-11 Anthropic 提出，2025-12 捐给 Linux 基金会。
>
> 一句话理解：**USB-C for AI tools**。一个 MCP Server 实现一次，所有支持 MCP 的 Agent 都能用。OpenAI、Cursor、Google 全部接入了。
>
> 2026 年想理解 Agent 生态，**绕不开 MCP**。"

### Hooks（30 秒）

> "Claude Code 的 Hooks——在 PreToolUse / PostToolUse 用外部进程做硬拦截。比如自动 lint、自动 git commit、敏感操作通过 Slack 审批。
>
> 和权限系统功能重叠但**更可定制**——前者内建、后者外置。"

[投屏：§8 末尾路线图链接]

> "完整的 P0 / P1 / P2 路线我整理在 `docs/2026-coding-agent-gap-analysis.md`，有 16 项能力的对比表。会后大家可以详读。"

---

## 24:00 - 25:00 · §9 三个原理统一

[投屏：§9]

> "最后我想留给大家一个统一的心智模型。
>
> 我们今天讲的几个原理——权限系统、Cache-aware 注入、Subagent 防递归——表面是不同话题，但背后是**同一个思想**：
>
> **从架构层画边界，不靠 prompt 劝模型。**
>
> 权限系统是工具调用层拦截；Cache-aware 是消息结构层重新分配；防递归是工具集合层断绝。
>
> 这条原则我建议大家以后做任何 Agent 设计都记住——**Prompt 是描述意图的，可以被 LLM 违反；架构是保证边界的，LLM 没法违反**。
>
> 这是我做这个项目最大的收获。"

---

## 25:00 - 30:00 · Q&A

[投屏：§10 总结页 → 留 Q&A]

> "今天分享请记住三件事：
>
> 一，Agent 的核心是 ReAct + Function Calling，不复杂；
> 二，上下文工程才是 Coding Agent 的灵魂；
> 三，架构边界永远比 prompt 边界可靠。
>
> 问答时间。"

---

## Q&A · 准备好的应对

### Q: 这个项目和 LangChain 比有什么区别？

> "LangChain 是产品级 Agent 框架，目标是让你少写代码就能跑起来。我们这个项目是**反框架**——目标是让你看得到每一行原理。一个是工具，一个是教材。"

### Q: 为什么不用 Spring AI / LangChain4j？

> "如果做产品就该用，这些框架在 Java 生态做得很好。但学习项目用了框架就违背初衷——你看到的是框架抽象，看不到底层。"

### Q: 现在 Cursor / Claude Code 这么强了，自己搭这个有意义吗？

> "正是因为它们强了，更需要理解原理才能用好。看了原理你会知道：为什么 system prompt 不能塞动态内容、为什么大任务该用 subagent、为什么权限规则不能依赖 LLM 自觉。这些理解能直接转化成更好的使用习惯。"

### Q: 有打算实现 MCP 吗？

> "这是 P0 路线里的一项，已经在 docs 里规划了最小实现路径。下个 Sprint 计划做。MCP 是 Agent 生态绕不开的东西，必须啃。"

### Q: 这个项目支持本地模型吗（Ollama 之类）？

> "只要那个本地模型支持 OpenAI Function Calling 协议就可以。改一下 api.base-url 配置就行，代码层零修改。"

### Q: 性能怎么样，能上生产吗？

> "**完全不能**。这是学习项目，没有重试、没有限流、没有并发、没有错误恢复。所有这些都是产品框架的核心能力，但和'看穿原理'的目标无关，所以刻意没做。"

### Q: 准备写多大规模？

> "刻意保持小。规划里的 P0 / P1 / P2 全部做完估计 **< 5000 行 Java**。如果超过 1 万行说明我做错了——'用最少代码演示原理'是这个项目的护城河。"

### Q: 为什么用 DeepSeek 不用 Claude？

> "纯成本考虑——DeepSeek 便宜 10 倍以上而且对 Function Calling 兼容良好。Claude 的优势在 Anthropic 自家 Prompt Caching，但学习项目没必要绑死一家。"

### Q: TodoList 和 Skill 有什么区别？

> "TodoList 是**会话内的工作记忆**——动态、轻量、当次任务完了就重置。Skill 是**跨会话的能力包**——定义、静态、要主动激活才生效。一个偏运行时，一个偏配置。"

### Q: Cache-aware 那个设计在 OpenAI API 上有效吗？

> "有效，OpenAI 的 prompt caching 也是 prefix matching——按 1024 token 起步、128 token 增量。只要 system 静态就能命中。我们的设计跨提供方通用。"

### Q: 如果我自己想做一个 agent，最重要的经验是什么？

> "投屏滑到 §11 附录——我整理了 6 类陷阱，都是我自己踩过的。最重要的三条：
>
> 一，temperature 设低（Agent 不是创作，要可复现）；
> 二，max_iterations 必须有（防 ping-pong）；
> 三，工具错误消息是给 LLM 读的，不是给人读的 stack trace。
>
> 其他细节 §11 里有完整清单，会后可以详读。"

### 如果没人提问

> "如果暂时没问题，可以会后找我聊。我把项目链接发到群里，欢迎 PR、欢迎 issue 讨论。今天感谢大家时间！"

---

## 🚨 全场紧急情况备案

| 情况 | 应对 |
|---|---|
| LLM API 中途挂了 | "网络问题我们跳过 demo，直接看代码" → 切到 IDE 走读关键文件 |
| Agent 输出乱码 | 重启 agent，"演示日常翻车，正好说明 LLM 的不确定性" |
| Permission 没生效 | 检查 permissions.json 是否拼写正确，或临时演示 docs 里的实现 |
| 时间超了 5 分钟 | 跳过"延伸话题"3 分钟，直接进 Q&A |
| 时间不够（少 5 分钟） | 跳过 Demo 3 (权限)，把 Cache-aware 段压到 3 分钟 |
| 听众明显走神 | 切回 Demo——任何时候都可以再起一个 prompt 示范 |
| 投屏断了 | 切到 IDE 直接读源码，反正所有原理都能在代码里找到 |

---

## 🎯 演讲时的几个肌肉记忆点

1. **每讲完一个原理，停 1 秒**——给听众一个"消化锚点"
2. **金句要慢**——"从架构层画边界，不靠 prompt 劝模型" 这句要刻意放慢
3. **Demo 出问题时不要慌**——LLM 翻车很正常，反而能强化"它不是确定性系统"的认知
4. **Cache-aware 段是高潮**——准备好语调升起来：*"这一节是今天我**最想讲清楚**的"*
5. **结尾的三件事**：
   - "Agent 的核心是 ReAct + Function Calling，**不复杂**"
   - "上下文工程才是 Coding Agent 的**灵魂**"
   - "架构边界永远**比 prompt 边界可靠**"

   背好这三句话——结尾必须干脆。

---

## 收尾仪式

> "三件事请记住：
> 一，Agent 的核心是 ReAct + Function Calling，不复杂；
> 二，上下文工程才是 Coding Agent 的灵魂；
> 三，架构边界永远比 prompt 边界可靠。
>
> 项目链接稍后发群里，欢迎大家来 PR、来吐槽。谢谢！"

[鞠躬 / 互动 / 散会]
