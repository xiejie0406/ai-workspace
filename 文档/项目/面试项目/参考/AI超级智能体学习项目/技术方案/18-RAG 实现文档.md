# Spring AI RAG 实现文档

本文档详细说明项目中 RAG（检索增强生成）的两种实现方式：
1. **本地知识库实现**（PgVector 向量数据库）
2. **云知识库实现**（阿里云百炼平台）

---

## 1. RAG 概述

### 1.1 什么是 RAG？

RAG（Retrieval-Augmented Generation，检索增强生成）是一种结合检索和生成的 AI 技术：

- **解决的问题**：LLM 幻觉、知识过时、缺乏专业领域知识
- **核心思想**：先从知识库检索相关信息，再让 LLM 基于检索结果生成回答

### 1.2 RAG 工作流程

```
用户提问: "如何追求女生?"
    │
    ▼
┌─────────────────────────────────────┐
│ 1. 检索（Retrieval）                 │
│    将问题向量化 → 相似度检索         │
│    返回最相关的 TopK 个文档          │
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│ 2. 增强（Augmentation）              │
│    将检索到的文档作为上下文          │
│    注入到 Prompt 中                  │
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────┐
│ 3. 生成（Generation）                │
│    LLM 基于上下文生成回答            │
│    回答更准确、更专业                │
└─────────────────────────────────────┘
```

---

## 2. 本地知识库实现（PgVector）

### 2.1 架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        本地知识库 RAG 架构                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────────┐    ┌──────────────────────┐    ┌─────────────────────┐   │
│  │ Markdown     │    │ LoveAppDocumentLoader│    │ EmbeddingModel      │   │
│  │ 知识库文档   │ ─→ │ 文档加载器            │ ─→ │ (DashScope)         │   │
│  │ resources/   │    │ 解析+分块+元数据     │    │ 文本→向量           │   │
│  │ document/    │    └──────────────────────┘    └─────────────────────┘   │
│  └──────────────┘                                         │                │
│                                                           ▼                │
│                                              ┌─────────────────────────┐   │
│                                              │ PostgreSQL + pgvector   │   │
│                                              │ vector_store 表         │   │
│                                              │ - id (UUID)             │   │
│                                              │ - content (TEXT)        │   │
│                                              │ - metadata (JSONB)      │   │
│                                              │ - embedding (vector)    │   │
│                                              └─────────────────────────┘   │
│                                                           │                │
│  ┌──────────────┐    ┌──────────────────────┐            │                │
│  │ 用户提问     │ ─→ │ QuestionAnswerAdvisor│ ←──────────┘                │
│  │ "如何追求"  │    │ 检索 + 增强 Prompt   │                             │
│  └──────────────┘    └──────────────────────┘                             │
│                              │                                             │
│                              ▼                                             │
│                      ┌──────────────┐                                      │
│                      │ LLM (通义千问)│                                      │
│                      │ 生成回答      │                                      │
│                      └──────────────┘                                      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 相关代码文件

| 文件 | 作用 |
|------|------|
| `PgVectorVectorStoreConfig.java` | 配置 PgVector 向量数据库 |
| `LoveAppDocumentLoader.java` | 加载知识库文档 |
| `LoveApp.java` | 使用 QuestionAnswerAdvisor 实现 RAG |
| `application.yml` | 数据库连接配置 |

### 2.3 核心代码

#### 2.3.1 向量库配置 - PgVectorVectorStoreConfig.java

```java
package com.yupi.yuaiagent.rag;

import jakarta.annotation.Resource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

@Configuration
public class PgVectorVectorStoreConfig {

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

    @Bean
    public VectorStore pgVectorVectorStore(JdbcTemplate jdbcTemplate, 
                                            EmbeddingModel dashscopeEmbeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(1536)              // 向量维度（与 EmbeddingModel 一致）
                .distanceType(COSINE_DISTANCE) // 余弦相似度
                .indexType(HNSW)               // HNSW 索引（加速检索）
                .initializeSchema(false)       // 手动创建表
                .schemaName("public")
                .vectorTableName("vector_store")
                .maxDocumentBatchSize(10000)
                .build();
    }
}
```

#### 2.3.2 文档加载器 - LoveAppDocumentLoader.java

```java
package com.yupi.yuaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LoveAppDocumentLoader {

    private final ResourcePatternResolver resourcePatternResolver;
    private static final String DOCUMENT_PATTERN = "classpath:document/*.md";

    public LoveAppDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    public List<Document> loadMarkdowns() {
        List<Document> allDocuments = new ArrayList<>();
        
        Resource[] resources = resourcePatternResolver.getResources(DOCUMENT_PATTERN);
        
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            // 从文件名提取状态标签（单身/恋爱/已婚）
            String status = filename.substring(filename.length() - 6, filename.length() - 4);

            // 配置 Markdown 解析器
            MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                    .withHorizontalRuleCreateDocument(true)  // 按 --- 分割文档
                    .withIncludeCodeBlock(false)
                    .withAdditionalMetadata("filename", filename)
                    .withAdditionalMetadata("status", status)  // 添加状态元数据
                    .build();

            MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
            allDocuments.addAll(reader.get());
        }
        
        return allDocuments;
    }
}
```

#### 2.3.3 对话中使用 RAG - LoveApp.java

```java
package com.yupi.yuaiagent.app;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class LoveApp {

    @Resource
    private ChatClient chatClient;
    
    @Resource
    private VectorStore pgVectorVectorStore;

    /**
     * 流式对话（集成 RAG）
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // ★ 核心：QuestionAnswerAdvisor 实现 RAG ★
                .advisors(new QuestionAnswerAdvisor(pgVectorVectorStore))
                .stream()
                .content();
    }
}
```

#### 2.3.4 数据库配置 - application.yml

```yaml
spring:
  # PgVector 数据库配置
  datasource:
    url: jdbc:postgresql://39.102.215.183:5432/postgres
    username: postgres
    password: your_password
    driver-class-name: org.postgresql.Driver
  
  ai:
    # PgVector 向量存储配置
    vectorstore:
      pgvector:
        index-type: HNSW
        dimensions: 1536
        distance-type: COSINE_DISTANCE
```

### 2.4 数据库表结构

```sql
-- 向量存储表
CREATE TABLE vector_store (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    content TEXT,                    -- 文档内容
    metadata JSONB,                  -- 元数据 {"status": "单身", "filename": "..."}
    embedding vector(1536)           -- 1536 维向量
);

-- HNSW 索引（加速相似度查询）
CREATE INDEX vector_store_embedding_idx 
ON vector_store USING hnsw (embedding vector_cosine_ops);
```

### 2.5 优化参数

```java
// 自定义检索参数
.advisors(new QuestionAnswerAdvisor(
    pgVectorVectorStore,
    SearchRequest.builder()
        .topK(10)                    // 返回前 10 条最相似文档
        .similarityThreshold(0.7)    // 相似度阈值
        .filterExpression("status == '单身'")  // 按元数据过滤
        .build()
))
```

---

## 3. 云知识库实现（阿里云百炼）

### 3.1 架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        云知识库 RAG 架构                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                     阿里云百炼平台                                    │  │
│  │  ┌────────────────────────────────────────────────────────────────┐  │  │
│  │  │ 知识库: "恋爱大师"                                               │  │  │
│  │  │ - 上传 PDF/Word/Markdown 文档                                   │  │  │
│  │  │ - 自动分块、向量化、建立索引                                     │  │  │
│  │  │ - 提供检索 API                                                  │  │  │
│  │  └────────────────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                     ▲                                      │
│                                     │ HTTPS API                            │
│                                     │                                      │
│  ┌──────────────┐    ┌──────────────────────────────────────┐             │
│  │ 用户提问     │ ─→ │ RetrievalAugmentationAdvisor          │             │
│  │ "如何表白"  │    │ + DashScopeDocumentRetriever          │             │
│  └──────────────┘    └──────────────────────────────────────┘             │
│                              │                                             │
│                              ▼                                             │
│                      ┌──────────────┐                                      │
│                      │ LLM (通义千问)│                                      │
│                      │ 生成回答      │                                      │
│                      └──────────────┘                                      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 相关代码文件

| 文件 | 作用 |
|------|------|
| `LoveAppRagCloudAdvisorConfig.java` | 配置云端 RAG Advisor |
| `LoveApp.java` | 使用 loveAppRagCloudAdvisor |
| `application.yml` | 配置 DashScope API Key |

### 3.3 核心代码

#### 3.3.1 云知识库配置 - LoveAppRagCloudAdvisorConfig.java

```java
package com.yupi.yuaiagent.rag;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetriever;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LoveAppRagCloudAdvisorConfig {

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    // 知识库索引名（需在百炼平台创建）
    private static final String KNOWLEDGE_INDEX = "恋爱大师";

    @Bean
    public Advisor loveAppRagCloudAdvisor() {
        // 1. 初始化 DashScope API 客户端
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();

        // 2. 创建云端文档检索器
        DocumentRetriever dashScopeDocumentRetriever = new DashScopeDocumentRetriever(
                dashScopeApi,
                DashScopeDocumentRetrieverOptions.builder()
                        .withIndexName(KNOWLEDGE_INDEX)
                        // .withTopK(5)                  // 可选：返回前 5 条
                        // .withSimilarityThreshold(0.5) // 可选：相似度阈值
                        .build()
        );

        // 3. 组装 RAG Advisor
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(dashScopeDocumentRetriever)
                .build();
    }
}
```

#### 3.3.2 对话中使用云 RAG - LoveApp.java

```java
@Component
public class LoveApp {

    @Resource
    private Advisor loveAppRagCloudAdvisor;

    public String doChatWithCloudRag(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // ★ 使用云端 RAG Advisor ★
                .advisors(loveAppRagCloudAdvisor)
                .call()
                .content();
    }
}
```

#### 3.3.3 配置 - application.yml

```yaml
spring:
  ai:
    dashscope:
      api-key: sk-your-api-key-here  # 阿里云 DashScope API Key
```

### 3.4 百炼平台配置步骤

1. **登录百炼平台**：https://bailian.console.aliyun.com
2. **创建知识库**：进入「知识库管理」→「新建知识库」
3. **上传文档**：支持 PDF、Word、Markdown、TXT 等格式
4. **等待索引构建**：平台自动分块、向量化、建立索引
5. **获取索引名**：确保与代码中 `KNOWLEDGE_INDEX` 一致

---

## 4. 两种方案对比

| 特性 | 本地知识库 (PgVector) | 云知识库 (阿里云百炼) |
|------|---------------------|---------------------|
| **运维成本** | 高（需维护数据库） | 低（免运维） |
| **数据安全** | 高（数据在本地） | 中（数据在云端） |
| **扩展性** | 需手动扩容 | 自动扩缩容 |
| **成本模式** | 服务器/数据库费用 | 按 API 调用计费 |
| **自定义能力** | 完全可控 | 较弱 |
| **离线使用** | 支持 | 不支持 |
| **适用场景** | 生产环境、敏感数据 | 快速验证、小规模应用 |

---

## 5. 切换方案

### 5.1 使用本地知识库（当前配置）

```java
// LoveApp.java
.advisors(new QuestionAnswerAdvisor(pgVectorVectorStore))
```

### 5.2 切换到云知识库

```java
// LoveApp.java
.advisors(loveAppRagCloudAdvisor)
```

---

## 6. 日志验证

启动项目后，可以在日志中看到 RAG 的工作过程：

```
INFO  c.y.yuaiagent.advisor.MyLoggerAdvisor : AI Request: Prompt{
    messages=[
        SystemMessage{...},
        UserMessage{content='如何追求女生

        Context information is below...
        ---------------------
        追女生第一步：了解对方的兴趣爱好，找到共同话题
        程序员追女生的正确姿势：用代码写情书
        ...
        ---------------------
        
        Given the context... reply to the user.'}
    ]
}
```

这表明 RAG 成功检索了知识库内容并注入到 Prompt 中。
