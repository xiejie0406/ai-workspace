# AI 超级智能体项目 — 技术学习文档

> 本文档基于 yu-ai-agent 项目源码，详细说明项目中涉及的各项 AI 技术概念、原理及实战用法。

---

## 1. 主流 AI 应用平台的使用

### 1.1 阿里云 DashScope（通义千问）

项目主力模型平台，通过 Spring AI Alibaba 集成：

- 模型：`qwen-plus`（通义千问增强版）
- 能力：文本对话、Embedding 向量化、工具调用
- 配置位置：`src/main/resources/application.yml`

```yaml
spring:
  ai:
    dashscope:
      api-key: sk-xxx
      chat:
        options:
          model: qwen-plus
```

### 1.2 阿里云百炼平台

项目中 RAG 知识库使用了百炼平台的云端知识库服务：

- 通过 `DashScopeDocumentRetriever` 对接百炼知识库索引
- 实现位置：`src/main/java/.../rag/LoveAppRagCloudAdvisorConfig.java`

```java
DashScopeDocumentRetriever retriever = new DashScopeDocumentRetriever(
    dashScopeApi,
    DashScopeDocumentRetrieverOptions.builder()
        .withIndexName("Ai智能体")
        .build()
);
```

### 1.3 Pexels 图片平台

MCP Server 中集成了 Pexels 图片搜索 API，用于多模态图片检索：

- 实现位置：`yu-image-search-mcp-server/.../tools/ImageSearchTool.java`

---

## 2. AI 大模型的 4 种接入方式

项目演示了 4 种不同的大模型调用方式，代码位于 `src/main/java/.../demo/invoke/` 目录：

### 2.1 方式一：Spring AI + DashScope（推荐）

通过 Spring AI 框架统一抽象层调用，解耦业务与模型：

```java
// SpringAiAiInvoke.java
@Resource
private ChatModel dashscopeChatModel;

public void aiInvoke() {
    Prompt prompt = new Prompt("你好，我是鱼皮");
    AssistantMessage output = dashscopeChatModel.call(prompt)
            .getResult().getOutput();
}
```

### 2.2 方式二：Spring AI + Ollama（本地模型）

调用本地部署的 Ollama 模型，接口与 DashScope 完全一致：

```java
// OllamaAiInvoke.java
@Resource
private ChatModel ollamaChatModel;
```

### 2.3 方式三：LangChain4j + DashScope

使用 LangChain4j 社区集成包调用 DashScope：

```java
// LangChain4jAiInvoke.java
QwenChatModel qwenChatModel = QwenChatModel.builder()
        .apiKey("sk-xxx")
        .modelName("qwen-plus")
        .build();
String answer = qwenChatModel.chat("你好");
```

### 2.4 方式四：原生 HTTP 调用

直接通过 HTTP 请求调用模型 API，不依赖任何框架：

```java
// RawAiInvoke.java
HttpRequest.post("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
    .header("Authorization", "Bearer sk-xxx")
    .body(jsonBody)
    .execute();
```

**对比总结：**

| 方式 | 优点 | 缺点 |
|------|------|------|
| Spring AI | 统一抽象、生态丰富、易切换模型 | 学习成本稍高 |
| Ollama | 本地运行、数据隐私、免费 | 需要 GPU 资源 |
| LangChain4j | 社区活跃、功能丰富 | Java 生态不如 Python 版成熟 |
| 原生 HTTP | 无依赖、完全可控 | 需自行处理序列化/错误/重试 |

---

## 3. AI 开发框架（Spring AI + LangChain4j）

### 3.1 Spring AI 框架

项目核心框架，版本 `1.0.0`，提供：

- `ChatModel`：统一的模型调用接口
- `ChatClient`：高级客户端，支持 Advisor 链、记忆、流式输出
- `VectorStore`：向量存储抽象
- `ToolCallback`：工具调用机制
- `MCP Client`：MCP 协议客户端

Maven 依赖：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter</artifactId>
    <version>1.0.0.2</version>
</dependency>
```

### 3.2 LangChain4j 框架

项目中作为对比演示，通过社区 DashScope 集成包使用：

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-community-dashscope</artifactId>
    <version>1.0.0-beta3</version>
</dependency>
```

### 3.3 两者对比

| 特性 | Spring AI | LangChain4j |
|------|-----------|-------------|
| 与 Spring 生态集成 | 原生支持 | 需适配 |
| 模型抽象 | ChatModel 统一接口 | ChatLanguageModel |
| Advisor/拦截器 | 内置 Advisor 链 | 需自行实现 |
| RAG 支持 | VectorStore + Advisor | 内置 RAG 管道 |
| 工具调用 | @Tool + ToolCallback | @Tool 注解 |

---

## 4. AI 大模型本地部署

### 4.1 Ollama 本地部署

项目通过 Ollama 部署本地模型 `gemma3:1b`：

```yaml
# application.yml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        model: gemma3:1b
```

**部署步骤：**

1. 安装 Ollama：从 https://ollama.com 下载
2. 拉取模型：`ollama pull gemma3:1b`
3. 启动服务：`ollama serve`（默认端口 11434）

### 4.2 本地模型的优势与限制

| 优势 | 限制 |
|------|------|
| 数据不出本地，隐私安全 | 需要 GPU 硬件资源 |
| 无 API 调用费用 | 小模型能力有限 |
| 无网络延迟 | 模型更新需手动操作 |
| 可离线使用 | 并发能力受限于硬件 |

---

## 5. Prompt 工程和优化技巧

### 5.1 系统提示词设计

项目中有两个典型的系统提示词设计：

**AI 恋爱大师提示词**（`LoveApp.java`）：

```java
private static final String SYSTEM_PROMPT = "你是一个专业的恋爱大师...";
```

设计要点：
- 明确角色定位（恋爱大师）
- 限定回答范围（情感咨询）
- 规定输出风格（专业、温暖）

**YuManus 智能体提示词**（`YuManus.java`）：

```java
private static final String SYSTEM_PROMPT = """
    You are YuManus, an all-capable AI assistant...
    """;

private static final String NEXT_STEP_PROMPT = """
    Based on user needs, proactively select the most appropriate tool...
    If you want to stop, use the `terminate` tool.
    """;
```

设计要点：
- 定义智能体身份和能力边界
- 明确工具选择策略
- 设定终止条件

### 5.2 Re-Reading（Re2）优化技巧

项目实现了 Re2 论文中的提示词优化技术，通过让模型"重新阅读"问题来提升推理质量：

```java
// ReReadingAdvisor.java
private static final String RE2_TEMPLATE = """
        %s
        Read the question again: %s
        """;
```

**原理：** 将用户问题重复一次附加到 Prompt 末尾，引导模型更仔细地理解问题，减少误解和遗漏。

### 5.3 查询重写优化

RAG 场景中，通过 `QueryRewriter` 对用户原始查询进行重写，提升检索召回率：

```java
// QueryRewriter.java
queryTransformer = RewriteQueryTransformer.builder()
        .chatClientBuilder(ChatClient.builder(dashscopeChatModel))
        .build();
```

**原理：** 利用大模型将口语化/模糊的查询改写为更精确的检索语句。

---

## 6. Spring AI 核心特性

### 6.1 自定义 Advisor（拦截器链）

Advisor 是 Spring AI 的核心扩展机制，类似 Spring MVC 的拦截器，可在 AI 请求/响应的生命周期中插入自定义逻辑。

**MyLoggerAdvisor — 日志拦截器：**

```java
// MyLoggerAdvisor.java
public class MyLoggerAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        request = before(request);   // 请求前：记录输入日志
        ChatClientResponse response = chain.nextCall(request);  // 传递给下一个 Advisor
        observeAfter(response);      // 响应后：记录输出日志
        return response;
    }
}
```

**ReReadingAdvisor — Re2 推理增强：**

```java
// ReReadingAdvisor.java
public class ReReadingAdvisor implements CallAdvisor, StreamAdvisor {
    private ChatClientRequest before(ChatClientRequest request) {
        String userText = request.prompt().getUserMessage().getText();
        String newUserText = RE2_TEMPLATE.formatted(userText, userText);
        // 修改用户消息，附加重复阅读指令
        return new ChatClientRequest(request.prompt().augmentUserMessage(newUserText), request.context());
    }
}
```

**Advisor 链的组装：**

```java
chatClient = ChatClient.builder(dashscopeChatModel)
    .defaultAdvisors(
        MessageChatMemoryAdvisor.builder(chatMemory).build(),  // 记忆 Advisor
        new MyLoggerAdvisor(),                                  // 日志 Advisor
        new ReReadingAdvisor()                                  // Re2 Advisor
    )
    .build();
```

### 6.2 对话记忆（Chat Memory）

项目实现了两种对话记忆方案：

**方案一：内存记忆（开发/测试用）**

```java
MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
    .chatMemoryRepository(new InMemoryChatMemoryRepository())
    .maxMessages(20)  // 保留最近 20 条消息
    .build();
```

**方案二：文件持久化记忆（Kryo 序列化）**

```java
// FileBasedChatMemory.java
public class FileBasedChatMemory implements ChatMemory {
    private static final Kryo kryo = new Kryo();

    @Override
    public void add(String conversationId, List<Message> messages) {
        List<Message> conversation = getOrCreateConversation(conversationId);
        conversation.addAll(messages);
        saveConversation(conversationId, conversation);  // Kryo 序列化到文件
    }
}
```

**记忆通过 Advisor 注入对话：**

```java
chatClient.prompt()
    .user(message)
    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
    .call();
```

### 6.3 结构化输出

Spring AI 支持将大模型的自由文本输出自动解析为 Java 对象：

```java
// 定义输出结构
record LoveReport(String title, List<String> suggestions) {}

// 调用时指定输出类型
LoveReport report = chatClient.prompt()
    .system(SYSTEM_PROMPT + "每次对话后都要生成恋爱结果...")
    .user(message)
    .call()
    .entity(LoveReport.class);  // 自动解析为结构化对象
```

**原理：** Spring AI 在 Prompt 中自动注入 JSON Schema 约束，引导模型输出符合格式的 JSON，再反序列化为 Java 对象。
