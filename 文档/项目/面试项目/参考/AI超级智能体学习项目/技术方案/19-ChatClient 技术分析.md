# ChatClient 与 Advisor 技术分析文档

## 概述

本文档针对 **AI 恋爱大师** 项目中使用的核心技术进行深入分析，包括：

- ChatClient 特性
- Advisors 机制
- Chat Memory Advisor
- Chat Memory

文档将从**如何使用**、**为什么这样实现**、**优点与不足**三个维度进行分析。

---

## 一、ChatClient 特性

### 1.1 项目中如何使用

#### 核心代码位置
- `src/main/java/com/yupi/yuaiagent/app/LoveApp.java`

#### 使用方式

```java
// 1. 通过 Builder 模式构建 ChatClient
chatClient = ChatClient.builder(dashscopeChatModel)
        .defaultSystem(SYSTEM_PROMPT)       // 设置默认系统提示词
        .defaultAdvisors(                   // 设置默认 Advisor 链
                MessageChatMemoryAdvisor.builder(chatMemory).build(),
                new MyLoggerAdvisor()
        )
        .build();

// 2. 使用流式 API 进行对话
ChatResponse chatResponse = chatClient
        .prompt()                           // 开始构建 Prompt
        .user(message)                      // 设置用户消息
        .advisors(spec -> spec.param(...))  // 传递 Advisor 参数
        .call()                             // 执行同步调用
        .chatResponse();                    // 获取响应对象
```

#### 项目中的具体应用

| 方法 | 调用方式 | 响应类型 | 用途 |
|------|---------|---------|------|
| `doChat()` | `.call().chatResponse()` | `ChatResponse` | 基础同步对话 |
| `doChatByStream()` | `.stream().content()` | `Flux<String>` | SSE 流式对话 |
| `doChatWithReport()` | `.call().entity(Class)` | 结构化对象 | 恋爱报告生成 |
| `doChatWithRag()` | `.call().chatResponse()` | `ChatResponse` | RAG 知识库问答 |
| `doChatWithTools()` | `.call().chatResponse()` | `ChatResponse` | 工具调用 |

### 1.2 为什么这样实现

#### 设计决策

1. **使用 Builder 模式构建**
   - 将 ChatClient 的创建放在构造函数中，实现单例复用
   - 通过 `defaultSystem` 和 `defaultAdvisors` 设置全局默认配置
   - 避免每次调用都重复配置

2. **采用流式 API 设计**
   - 链式调用使代码更清晰易读
   - 每个方法只做一件事，符合单一职责原则
   - 支持在调用时临时覆盖默认配置

3. **统一入口点**
   - 所有 AI 交互都通过 ChatClient 进行
   - 便于统一管理和维护

### 1.3 优点与不足

#### ✅ 优点

| 优点 | 说明 |
|------|------|
| **API 简洁** | 流式 API 设计，代码可读性高 |
| **配置灵活** | 支持默认配置 + 调用时覆盖 |
| **功能完整** | 同步/流式/结构化输出一应俱全 |
| **扩展性强** | Advisor 机制提供强大的扩展能力 |
| **类型安全** | 结构化输出支持直接返回 Java 对象 |

#### ❌ 不足

| 不足 | 说明 | 改进建议 |
|------|------|---------|
| **单例风险** | 构造函数中创建 ChatClient，配置固定 | 考虑使用工厂模式，支持动态创建 |
| **内存占用** | 基于内存的对话记忆可能导致内存增长 | 使用持久化存储或设置过期策略 |
| **错误处理** | 项目中未展示完整的异常处理 | 添加全局异常处理和重试机制 |
| **配置硬编码** | 系统提示词和配置写在代码中 | 建议外部化配置（配置文件或数据库） |

---

## 二、Advisors 机制

### 2.1 项目中如何使用

#### 核心代码位置
- `src/main/java/com/yupi/yuaiagent/advisor/MyLoggerAdvisor.java`
- `src/main/java/com/yupi/yuaiagent/advisor/ReReadingAdvisor.java`

#### 自定义 Advisor 实现

**1. MyLoggerAdvisor - 日志记录**

```java
@Slf4j
public class MyLoggerAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 0;  // 执行顺序
    }

    // 请求前处理
    private ChatClientRequest before(ChatClientRequest request) {
        log.info("AI Request: {}", request.prompt());
        return request;
    }

    // 响应后处理
    private void observeAfter(ChatClientResponse chatClientResponse) {
        log.info("AI Response: {}", chatClientResponse.chatResponse().getResult().getOutput().getText());
    }

    // 同步调用拦截
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        request = before(request);                      // 请求前
        ChatClientResponse response = chain.nextCall(request);  // 调用下一个
        observeAfter(response);                         // 响应后
        return response;
    }

    // 流式调用拦截
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        request = before(request);
        Flux<ChatClientResponse> responseFlux = chain.nextStream(request);
        // 使用聚合器在流完成后执行回调
        return new ChatClientMessageAggregator()
                .aggregateChatClientResponse(responseFlux, this::observeAfter);
    }
}
```

**2. ReReadingAdvisor - Re2 推理增强**

```java
public class ReReadingAdvisor implements CallAdvisor, StreamAdvisor {

    // 核心：改写 Prompt，添加重读指令
    private ChatClientRequest before(ChatClientRequest chatClientRequest) {
        String userText = chatClientRequest.prompt().getUserMessage().getText();
        
        // 修改用户提示词，让 AI 重读问题
        String newUserText = """
                %s
                Read the question again: %s
                """.formatted(userText, userText);
        
        Prompt newPrompt = chatClientRequest.prompt().augmentUserMessage(newUserText);
        return new ChatClientRequest(newPrompt, chatClientRequest.context());
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(this.before(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(this.before(request));
    }

    @Override
    public int getOrder() { return 0; }

    @Override
    public String getName() { return this.getClass().getSimpleName(); }
}
```

#### 项目中的 Advisor 注册方式

```java
// 方式1：构建时注册默认 Advisor
chatClient = ChatClient.builder(dashscopeChatModel)
        .defaultAdvisors(
                MessageChatMemoryAdvisor.builder(chatMemory).build(),
                new MyLoggerAdvisor()
        )
        .build();

// 方式2：调用时动态添加
chatClient.prompt()
        .user(message)
        .advisors(new MyLoggerAdvisor())  // 动态添加
        .advisors(new QuestionAnswerAdvisor(vectorStore))  // RAG Advisor
        .call();
```

### 2.2 为什么这样实现

#### 设计决策

1. **同时实现 CallAdvisor 和 StreamAdvisor**
   - 项目支持同步和流式两种调用方式
   - 确保两种方式都能被拦截处理

2. **使用 before/observeAfter 模式**
   - 将请求前和响应后的逻辑分离
   - 代码复用，同步和流式共用 before 逻辑

3. **流式响应使用 ChatClientMessageAggregator**
   - 流式响应是分片返回的
   - 使用聚合器在流完成后执行回调
   - 确保日志记录的是完整响应

4. **getOrder() 返回 0**
   - 日志 Advisor 应该最先执行
   - 确保能捕获原始请求

### 2.3 优点与不足

#### ✅ 优点

| 优点 | 说明 |
|------|------|
| **AOP 思想** | 无侵入地增强功能，不修改核心逻辑 |
| **职责分离** | 每个 Advisor 只做一件事 |
| **链式执行** | 支持多个 Advisor 组合使用 |
| **动态添加** | 可以在调用时按需添加 |
| **顺序可控** | 通过 getOrder() 控制执行顺序 |

#### ❌ 不足

| 不足 | 说明 | 改进建议 |
|------|------|---------|
| **日志过于详细** | 打印完整 Prompt 可能包含敏感信息 | 添加脱敏处理或日志级别控制 |
| **流式日志延迟** | 流式响应需等待完成才能记录 | 考虑实时记录分片 |
| **Order 冲突** | 两个 Advisor 的 order 都是 0 | 建议使用常量定义执行顺序 |
| **缺少配置化** | Advisor 行为硬编码 | 支持通过配置开关功能 |

---

## 三、Chat Memory Advisor

### 3.1 项目中如何使用

#### 核心代码

```java
// 1. 创建对话记忆存储
MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
        .chatMemoryRepository(new InMemoryChatMemoryRepository())
        .maxMessages(20)  // 最多保留20条消息
        .build();

// 2. 创建 MessageChatMemoryAdvisor
MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

// 3. 注册到 ChatClient
chatClient = ChatClient.builder(dashscopeChatModel)
        .defaultAdvisors(memoryAdvisor)
        .build();

// 4. 调用时传递会话ID
chatClient.prompt()
        .user(message)
        .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))  // 关键！
        .call();
```

### 3.2 为什么这样实现

#### 设计决策

1. **使用 MessageWindowChatMemory**
   - 滑动窗口策略，只保留最近 N 条消息
   - 避免 Prompt 过长导致超出模型限制
   - 控制 Token 消耗成本

2. **设置 maxMessages = 20**
   - 恋爱咨询场景需要较多上下文
   - 20 条消息足够理解对话背景
   - 平衡记忆深度和性能

3. **使用 InMemoryChatMemoryRepository**
   - 开发阶段快速验证
   - 无需额外依赖
   - 重启后记忆丢失（可接受）

4. **通过 Advisor 参数传递会话ID**
   - 支持多用户并发
   - 每个用户独立的对话历史
   - 使用 UUID 保证唯一性

### 3.3 优点与不足

#### ✅ 优点

| 优点 | 说明 |
|------|------|
| **透明集成** | 通过 Advisor 机制无缝集成 |
| **多会话支持** | conversation_id 隔离不同用户 |
| **窗口策略** | 滑动窗口避免无限增长 |
| **配置简单** | Builder 模式，配置清晰 |

#### ❌ 不足

| 不足 | 说明 | 改进建议 |
|------|------|---------|
| **内存存储** | 重启后数据丢失 | 使用 FileBasedChatMemory 或数据库 |
| **无过期机制** | 长时间不活跃的会话仍占用内存 | 添加 TTL 过期策略 |
| **固定窗口大小** | maxMessages 对所有用户统一 | 按场景动态调整 |
| **无用户关联** | chatId 与用户系统脱节 | 集成用户认证系统 |

---

## 四、Chat Memory

### 4.1 项目中如何使用

项目中实现了两种 Chat Memory：

#### 1. 基于内存（InMemoryChatMemoryRepository）

```java
// 来自 LoveApp.java
MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
        .chatMemoryRepository(new InMemoryChatMemoryRepository())
        .maxMessages(20)
        .build();
```

#### 2. 基于文件（FileBasedChatMemory）

```java
// 来自 src/main/java/com/yupi/yuaiagent/chatmemory/FileBasedChatMemory.java
public class FileBasedChatMemory implements ChatMemory {

    private final String fileDir;

    public FileBasedChatMemory(String fileDir) {
        this.fileDir = fileDir;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        List<Message> conversationMessages = getOrCreateConversation(conversationId);
        conversationMessages.addAll(messages);
        saveConversation(conversationId, conversationMessages);
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        List<Message> conversationMessages = getOrCreateConversation(conversationId);
        if (lastN > 0 && conversationMessages.size() > lastN) {
            return conversationMessages.subList(conversationMessages.size() - lastN, conversationMessages.size());
        }
        return conversationMessages;
    }

    @Override
    public void clear(String conversationId) {
        Path filePath = getFilePath(conversationId);
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to clear conversation: " + conversationId, e);
        }
    }

    // 使用 Kryo 序列化保存
    private void saveConversation(String conversationId, List<Message> messages) {
        // ... Kryo 序列化代码
    }
}
```

#### 使用方式切换

```java
// LoveApp.java 中的注释代码展示了切换方式

// 方式1：基于内存（当前使用）
MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
        .chatMemoryRepository(new InMemoryChatMemoryRepository())
        .maxMessages(20)
        .build();

// 方式2：基于文件（注释掉）
// String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";
// ChatMemory chatMemory = new FileBasedChatMemory(fileDir);
```

### 4.2 为什么这样实现

#### 设计决策

1. **两种实现并存**
   - 内存版：开发测试用，快速启动
   - 文件版：生产环境用，支持持久化

2. **使用 Kryo 序列化**
   - 比 Java 原生序列化更高效
   - 序列化后体积更小
   - 支持复杂对象结构

3. **文件按会话ID存储**
   - 每个会话一个文件
   - 便于管理和清理
   - 支持并发访问

4. **实现 ChatMemory 接口**
   - 符合 Spring AI 规范
   - 可以无缝替换内置实现
   - 便于与框架集成

### 4.3 优点与不足

#### ✅ 优点

| 优点 | 说明 |
|------|------|
| **持久化支持** | FileBasedChatMemory 支持跨重启保存 |
| **接口统一** | 都实现 ChatMemory 接口，可互换 |
| **Kryo 高效** | 序列化性能好，体积小 |
| **按会话隔离** | 每个会话独立存储 |

#### ❌ 不足

| 不足 | 说明 | 改进建议 |
|------|------|---------|
| **文件并发** | 高并发下可能有竞争问题 | 添加文件锁或使用数据库 |
| **无过期清理** | 历史文件不会自动删除 | 添加定时清理任务 |
| **容量无限制** | 单个会话可能无限增长 | 添加消息数量上限 |
| **缺少索引** | 无法按时间范围查询 | 使用数据库存储 |

---

## 五、技术选型总结

### 5.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              LoveApp                                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   ┌─────────────────────────────────────────────────────────────────┐  │
│   │                         ChatClient                               │  │
│   │  • defaultSystem(SYSTEM_PROMPT)                                  │  │
│   │  • defaultAdvisors(memoryAdvisor, loggerAdvisor)                 │  │
│   └──────────────────────────┬──────────────────────────────────────┘  │
│                              │                                          │
│              ┌───────────────┼───────────────┐                          │
│              ▼               ▼               ▼                          │
│   ┌──────────────────┐ ┌──────────────┐ ┌──────────────┐               │
│   │ MessageChatMemory│ │ MyLoggerAdvi │ │ Other Advisor│               │
│   │     Advisor      │ │    sor       │ │   (RAG等)    │               │
│   └────────┬─────────┘ └──────────────┘ └──────────────┘               │
│            │                                                            │
│            ▼                                                            │
│   ┌──────────────────┐                                                  │
│   │ MessageWindow    │                                                  │
│   │   ChatMemory     │                                                  │
│   └────────┬─────────┘                                                  │
│            │                                                            │
│            ▼                                                            │
│   ┌──────────────────┐     ┌──────────────────┐                        │
│   │ InMemoryChat     │ OR  │ FileBasedChat    │                        │
│   │ MemoryRepository │     │     Memory       │                        │
│   └──────────────────┘     └──────────────────┘                        │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### 5.2 推荐改进

| 优先级 | 改进项 | 说明 |
|-------|-------|------|
| 高 | 配置外部化 | 系统提示词、maxMessages 等配置外部化 |
| 高 | 错误处理 | 添加统一的异常处理和重试机制 |
| 中 | 日志脱敏 | 对敏感信息进行脱敏处理 |
| 中 | 会话过期 | 添加 TTL 机制，自动清理过期会话 |
| 低 | 数据库存储 | 使用 Redis 或数据库替代文件存储 |
| 低 | 监控指标 | 添加调用次数、延迟等监控指标 |

### 5.3 最佳实践建议

1. **ChatClient 使用**
   - 将 ChatClient 作为单例管理
   - 默认配置放在构建时，特殊配置放在调用时
   - 善用结构化输出简化数据处理

2. **Advisor 开发**
   - 每个 Advisor 职责单一
   - 合理设置 Order 避免冲突
   - 同时实现 CallAdvisor 和 StreamAdvisor

3. **Chat Memory 配置**
   - 开发环境用内存版，生产用持久化版
   - 根据场景调整 maxMessages
   - 实现过期清理机制

---

## 六、附录

### 6.1 相关代码文件

| 文件 | 说明 |
|------|------|
| `app/LoveApp.java` | ChatClient 使用入口 |
| `advisor/MyLoggerAdvisor.java` | 自定义日志 Advisor |
| `advisor/ReReadingAdvisor.java` | 自定义 Re2 Advisor |
| `chatmemory/FileBasedChatMemory.java` | 文件持久化记忆 |
| `controller/AiController.java` | HTTP 接口控制器 |

### 6.2 依赖版本

```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter</artifactId>
    <version>1.0.0-M6.1</version>
</dependency>
```

### 6.3 参考资料

- [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)
- [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba)
- [Re2 论文](https://arxiv.org/abs/2309.06275)
