# KnowFlow Phase 3 RAG 问答接口设计

> 阶段：Phase 3 RAG 问答与引用溯源  
> 目标：员工提问后，系统基于企业知识库流式回答，并返回引用来源。

## 1. 问答流程

```text
用户问题
  -> 权限过滤知识库
  -> Query Rewrite
  -> 向量召回
  -> 分段过滤/重排
  -> Prompt 组装
  -> LLM SSE 回答
  -> 返回引用
  -> 写入问答日志
```

## 2. 普通问答接口

```text
POST /api/knowflow/chat
```

请求：

```json
{
  "sessionId": 10001,
  "kbIds": ["product", "service-sop"],
  "question": "客户退货超过 7 天怎么办？",
  "stream": false
}
```

响应：

```json
{
  "answer": "超过 7 天的退货需要先判断是否属于质量问题...",
  "confidence": 0.82,
  "qaLogId": 90001,
  "citations": [
    {
      "index": 1,
      "documentName": "售后处理流程.docx",
      "pageNo": 12,
      "chunkId": 128,
      "score": 0.91,
      "snippet": "超过 7 天的申请需由客服主管审批..."
    }
  ]
}
```

## 3. SSE 问答接口

```text
GET /api/knowflow/chat/sse?sessionId=10001&question=...
```

事件类型：

| 事件 | 说明 |
| --- | --- |
| `rewrite` | 问题改写结果 |
| `retrieve_start` | 开始检索 |
| `retrieve_result` | 召回分段摘要 |
| `answer_delta` | 回答流式片段 |
| `citation` | 引用来源 |
| `done` | 完成，返回 qaLogId |
| `error` | 错误 |

## 4. 无答案策略

当最高相关度低于阈值时：

- 不编造答案；
- 返回无答案状态；
- 写入 `answer_quality = missed`；
- 前端提示创建知识补充任务。

## 5. 日志写入

每次问答都写入：

- 原始问题；
- 改写问题；
- 回答内容；
- 引用数量；
- 耗时；
- 命中知识库；
- 用户反馈初始状态。

## 6. 验收

- 前端能使用 SSE 接收回答；
- 回答带引用编号；
- 引用能查到 chunk；
- 无答案时不编造；
- 问答自动写入日志。
