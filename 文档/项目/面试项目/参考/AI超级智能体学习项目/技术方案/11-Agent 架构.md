# KnowFlow Phase 5 Agent 架构设计

> 阶段：Phase 5 Agent 诊断能力  
> 目标：在 Java / Spring AI 项目内实现知识库运营诊断 Agent。

## 1. Agent 定位

第一版不做复杂多 Agent，先做：

```text
KnowledgeDiagnosisAgent
```

它面向管理员，不面向普通聊天。

## 2. 输入

- 用户问题；
- AI 回答；
- 引用来源；
- 用户反馈；
- 知识库健康状态；
- 文档入库状态；
- 历史相似日志。

## 3. 输出

```json
{
  "riskLevel": "high",
  "problemType": "missing_document",
  "reason": "问题没有可靠引用，HR 制度库缺少离职交接制度",
  "suggestedActions": ["supplement_document", "rebuild_index"],
  "taskTitle": "补充离职交接制度",
  "confidence": 0.86
}
```

## 4. 架构

```text
Workflow 固定步骤
  -> Tool 查询业务数据
  -> LLM 分析原因
  -> 结构化输出
  -> 创建质量任务
```

## 5. 工具

| Tool | 作用 |
| --- | --- |
| `KnowledgeBaseTool` | 查询知识库健康状态 |
| `DocumentTool` | 查询文档和失败任务 |
| `ChunkTool` | 查询引用分段 |
| `QaLogTool` | 查询历史相似问题 |
| `QualityTaskTool` | 创建质量任务 |

## 6. 为什么先用 Java

当前项目已有 Spring AI、ReAct Agent、Tool Calling 和 RAG 代码基础。  
第一版用 Java 内置 Agent，系统边界更清晰，简历上也更容易讲成“传统业务系统内置智能体能力”。

## 7. 验收

- 对低评分日志能自动诊断原因；
- 能区分缺文档、引用不相关、分段差、权限问题；
- 输出 JSON 可直接创建质量任务；
- Agent 过程可记录到日志。
