# ReAct Coding Agent — Java 17 从零实现

一个用于学习 LLM Agent 工作原理的最简实现。不依赖任何 Agent 框架，仅用 **Java 17 + Gson + JDK HttpClient**，调用 OpenAI 兼容 API，实现完整的 **ReAct（Reasoning + Acting）循环**。

## 为什么做这个项目？

市面上的 Agent 框架（LangChain、Semantic Kernel 等）封装层次很深，初学者很难理解 Agent 到底是怎么工作的。本项目的目标是：

- **零框架依赖**：除了 JSON 解析（Gson），不引入任何 AI/Agent 框架
- **代码即文档**：14 个 Java 文件，每个都短小清晰，直接阅读源码就能理解原理
- **完整闭环**：从用户输入 → LLM 推理 → 工具调用 → 结果反馈 → 最终回答，覆盖 Agent 的核心机制

## ReAct 模式是什么？

ReAct（Reasoning + Acting）是一种让 LLM 交替进行"思考"和"行动"的 Agent 模式：

```
用户: "帮我看看当前目录有什么文件"

[Thought]  用户想查看当前目录的文件，我应该使用 list_files 工具
[Action]   list_files({"path": "."})
[Observe]  pom.xml
           agent.properties.example
           src/
           README.md
[Answer]   当前目录下有 4 个文件/目录：pom.xml、agent.properties.example、src/ 和 README.md
```

LLM 不是一次性给出答案，而是通过多轮"思考 → 调用工具 → 观察结果"的循环来完成复杂任务。这正是 OpenAI Function Calling 机制的核心用法。

## 项目结构

```
code-agent-native-demo/
├── pom.xml                              # Maven 构建，唯一外部依赖：Gson
├── agent.properties.example             # 配置模板（复制为 agent.properties 后再填写）
├── README.md
└── src/main/java/agent/
    ├── Main.java                        # 入口：REPL 交互循环
    ├── AgentConfig.java                 # 加载 agent.properties 配置
    ├── LLMClient.java                   # HttpClient 封装，调用 chat completions
    ├── ReActLoop.java                   # 核心：ReAct 循环编排
    ├── ConversationHistory.java         # 管理对话消息列表
    ├── ConsoleRenderer.java             # 终端彩色输出 Thought/Action/Observation
    ├── ToolRegistry.java                # 工具注册、schema 生成、分发执行
    ├── ToolDefinition.java              # Tool → OpenAI function schema 转换
    ├── ToolResult.java                  # record：success + output
    └── tools/
        ├── Tool.java                    # 工具接口
        ├── ReadFileTool.java            # 读文件
        ├── WriteFileTool.java           # 写文件
        ├── ListFilesTool.java           # 列目录
        └── ExecuteCommandTool.java      # 执行 shell 命令
```

## 技术选型

| 选择 | 理由 |
|------|------|
| Java 17 | LTS 版本，支持 record、text block、`HttpClient` 等现代特性 |
| Maven | Java 项目标准构建工具，零学习成本 |
| Gson 2.13.x | 轻量 JSON 库，API 简单，适合手动构建 JSON |
| `java.net.http.HttpClient` | JDK 内置，无需额外 HTTP 依赖 |
| `.properties` 配置 | Java 原生支持，无需 YAML/TOML 等额外库 |
| `JsonObject` 直接操作 | 不做 POJO 映射，让学习者直接看到 API 的 JSON 结构 |

## 快速开始

### 前置条件

- Java 17+
- Maven 3.6+
- 一个 OpenAI API Key（或兼容 API 的 Key）

### 1. 配置 API Key

方式一：设置环境变量（推荐）

```bash
export OPENAI_API_KEY=sk-your-api-key-here
```

方式二：复制配置模板并编辑本地配置文件

```bash
cp agent.properties.example agent.properties

# 编辑 agent.properties，将 api.key 改为你的实际 Key
```

### 2. 编译并运行

```bash
# 方式一：直接编译运行
mvn compile exec:java

# 方式二：打包为 fat jar 后运行
mvn package
java -jar target/code-agent-1.0.0.jar
```

### 3. 开始对话

```
╔══════════════════════════════════════╗
║   ReAct Coding Agent (Java 17)      ║
╚══════════════════════════════════════╝

Model: gpt-4o
Type your request (or 'exit' to quit):

> What files are in the current directory?
────────────────────────────────────────────────────────
[Thought] 用户想查看当前目录的文件列表，我来调用 list_files 工具
[Action]  list_files({"path":"."})
[Observe] [DIR]  src
          [DIR]  target
                 agent.properties.example
                 pom.xml
                 README.md
────────────────────────────────────────────────────────
[Answer]  当前目录下有以下文件和目录：...
```

输入 `exit` 或 `quit` 退出。

## 配置说明

先复制模板文件：

```bash
cp agent.properties.example agent.properties
```

所有配置项都在本地的 `agent.properties` 文件中：

```properties
# API 密钥（留空则从环境变量 OPENAI_API_KEY 读取）
api.key=your-api-key-here

# API 基础 URL（可替换为兼容的第三方服务）
api.base-url=https://api.openai.com/v1

# 使用的模型名称
api.model=gpt-4o

# ReAct 最大迭代次数（防止无限循环）
agent.max-iterations=15

# 系统提示词（定义 Agent 的角色和行为策略）
agent.system-prompt=You are a helpful coding agent...
```

### 使用其他兼容 API

本项目兼容任何提供 OpenAI 格式 `/v1/chat/completions` 接口的服务，只需修改 `api.base-url` 和 `api.model`：

```properties
# 示例：使用 DeepSeek
api.base-url=https://api.deepseek.com/v1
api.model=deepseek-chat
```

## 四个内置工具

| 工具 | 参数 | 功能 |
|------|------|------|
| `read_file` | `path` | 读取文件内容，超过 10000 字符自动截断 |
| `write_file` | `path`, `content` | 写入文件，自动创建父目录 |
| `list_files` | `path`, `recursive`(可选) | 列出目录内容，目录标记 `[DIR]` 前缀 |
| `execute_command` | `command` | 通过 `/bin/sh -c` 执行 shell 命令，30 秒超时 |

## 核心流程详解

### ReAct 循环（`ReActLoop.java`）

这是整个项目的核心，流程如下：

```
用户输入 → 加入 ConversationHistory
        ↓
  ┌─→ 调用 LLM（发送 messages + tools）
  │       ↓
  │   响应中有 tool_calls？
  │     ├─ 没有 → 提取 content，输出最终回答，结束
  │     └─ 有：
  │         1. 提取 content → 显示 [Thought]
  │         2. 将完整的 assistant message 加入历史
  │         3. 遍历每个 tool_call：
  │            a. 显示 [Action]: tool_name(args)
  │            b. 执行工具，获取 ToolResult
  │            c. 显示 [Observation]: result
  │            d. 将 tool result（role=tool）加入历史
  │         4. 检查是否超过 maxIterations
  └──────── 继续循环（LLM 会看到工具执行结果）
```

### OpenAI Function Calling 请求格式

发送给 API 的请求体结构：

```json
{
  "model": "gpt-4o",
  "messages": [
    {"role": "system", "content": "You are a helpful coding agent..."},
    {"role": "user", "content": "读取 pom.xml 文件"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "read_file",
        "description": "Read the contents of a file...",
        "parameters": {
          "type": "object",
          "properties": {
            "path": {"type": "string", "description": "The file path to read"}
          },
          "required": ["path"]
        }
      }
    }
  ]
}
```

LLM 决定调用工具时，响应中会包含：

```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": "让我读取 pom.xml 文件的内容。",
      "tool_calls": [{
        "id": "call_abc123",
        "type": "function",
        "function": {
          "name": "read_file",
          "arguments": "{\"path\": \"pom.xml\"}"
        }
      }]
    },
    "finish_reason": "tool_calls"
  }]
}
```

我们执行工具后，将结果以 `role: "tool"` 消息送回：

```json
{"role": "tool", "tool_call_id": "call_abc123", "content": "文件内容..."}
```

然后再次调用 LLM，它会基于工具结果生成最终回答。

### 关键类职责

| 类 | 职责 |
|---|------|
| `Main` | 程序入口，初始化所有组件，运行 REPL 交互循环 |
| `AgentConfig` | 加载 `agent.properties`，支持环境变量回退 |
| `LLMClient` | 封装 `HttpClient`，构建请求 JSON，发送到 `/chat/completions` |
| `ReActLoop` | 核心编排：调用 LLM → 判断是否有工具调用 → 执行 → 循环 |
| `ConversationHistory` | 维护 `messages` 数组（system / user / assistant / tool） |
| `ConsoleRenderer` | ANSI 彩色输出，区分 Thought / Action / Observation / Answer |
| `ToolRegistry` | 注册工具、生成 `tools` JSON 数组、按名称分发执行 |
| `ToolDefinition` | 将 `Tool` 接口转换为 OpenAI function schema 格式 |
| `ToolResult` | record 类型，携带 `success` 标志和 `output` 文本 |

## 如何扩展新工具

1. 创建一个新类实现 `Tool` 接口：

```java
package agent.tools;

import agent.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class SearchFileTool implements Tool {

    @Override
    public String name() {
        return "search_file";
    }

    @Override
    public String description() {
        return "Search for a pattern in files using grep";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();

        JsonObject pattern = new JsonObject();
        pattern.addProperty("type", "string");
        pattern.addProperty("description", "The regex pattern to search for");
        props.add("pattern", pattern);

        JsonObject path = new JsonObject();
        path.addProperty("type", "string");
        path.addProperty("description", "The directory to search in");
        props.add("path", path);

        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("pattern");
        required.add("path");
        schema.add("required", required);
        return schema;
    }

    @Override
    public ToolResult execute(JsonObject args) {
        // 实现搜索逻辑...
        return ToolResult.success("search results here");
    }
}
```

2. 在 `ToolRegistry` 构造函数中注册：

```java
public ToolRegistry() {
    register(new ReadFileTool());
    register(new WriteFileTool());
    register(new ListFilesTool());
    register(new ExecuteCommandTool());
    register(new SearchFileTool());  // 添加这一行
}
```

完成，无需修改任何其他代码。LLM 会自动在 `tools` 参数中看到新工具的 schema，并在合适的时候调用它。

## 验证测试

启动 Agent 后，可以用以下对话验证各功能：

| 输入 | 预期行为 |
|------|---------|
| `What files are in the current directory?` | 调用 `list_files`，显示完整 ReAct 流程 |
| `Create a file called hello.txt with "Hello World" in it` | 调用 `write_file` 创建文件 |
| `Read hello.txt` | 调用 `read_file` 返回内容 |
| `Run the command: echo "Hello from shell"` | 调用 `execute_command` 执行命令 |
| `Read pom.xml and tell me what dependencies this project has` | 调用 `read_file`，然后分析内容给出回答 |

## 学习建议

推荐按以下顺序阅读源码：

1. **`Main.java`** — 了解整体组装方式，看各组件如何串联
2. **`Tool.java` + `ReadFileTool.java`** — 理解工具接口和一个具体实现
3. **`ToolDefinition.java` + `ToolRegistry.java`** — 理解工具如何转为 OpenAI function schema
4. **`LLMClient.java`** — 理解如何构建和发送 API 请求
5. **`ConversationHistory.java`** — 理解多轮对话的消息管理
6. **`ReActLoop.java`** — 这是核心，理解 Thought → Action → Observation 循环
7. **`ConsoleRenderer.java`** — 了解终端彩色输出的实现

## License

MIT
