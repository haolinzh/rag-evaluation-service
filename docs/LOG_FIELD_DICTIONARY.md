# 日志字段字典

本文档说明后端持久化的两类数据：`request_log` 表（以「请求」为 entry 的明细）与运维指标 CSV（`GET /api/report/csv`）。

---

## 1. request_log 表字段

以每次问答请求为一行，由 `ChatService.logRequest()` 写入 PostgreSQL。前端入口：主页「日志」面板（每 5 秒刷新）+「日志详情」页（全量明细）。

| 字段（JSON） | 类型 | 说明 |
|---|---|---|
| `id` | long | 自增主键 |
| `requestId` | string | 请求唯一 ID（MDC traceId，贯穿整个请求链路） |
| `sessionId` | string | 会话 ID，未传时后端自动生成 UUID |
| `createdAt` | datetime | 写入时间（`@PrePersist` 自动填充） |
| `question` | string | 用户问题，**已做 PII 脱敏**（手机号/身份证/邮箱中段星号掩码） |
| `answer` | string | 生成的回答；`error` 状态时为 `null` |
| `model` | string | 对话模型（`qwen-turbo`，可切换 `qwen-plus`/`qwen-max` 等） |
| `retrievalMode` | string | 实际生效的检索模式：`vector` / `hybrid` / `hybrid-rerank` |
| `hitDocuments` | string | 命中的文档文件名（去重，逗号分隔） |
| `responseTimeMs` | long | 总耗时（毫秒） |
| `llmCallCount` | int | 调用大模型的次数（当前单次请求为 1；缓存命中为 0） |
| `cacheHit` | bool | 是否命中语义缓存 |
| `refusal` | bool | 是否拒答 |
| `refusalReason` | string | 拒答原因（`REFUSE_SAFETY_VIOLATION` / `REFUSE_LOW_CONFIDENCE` / `REFUSE_OUT_OF_SCOPE`） |
| `retrievalLatencyMs` | long | 检索耗时（毫秒） |
| `generationLatencyMs` | long | 生成耗时（毫秒） |
| `promptTokens` | int | prompt token 估算（`len/1.5`） |
| `completionTokens` | int | 生成 token 估算 |
| `chunksRetrieved` | int | 召回 chunk 数 |
| `maxChunkScore` | double | 召回 chunk 的最高相似度分 |
| `piiRedactions` | int | 回答中脱敏的 PII 数量 |
| `status` | string | `success` / `refused` / `error` |

---

## 2. 运维指标 CSV 列

`GET /api/report/csv` 输出逐请求明细行 + 末尾汇总行。

**明细行**（表头）：

```
requestId, sessionId, timestamp, retrievalMode,
retrievalLatencyMs, generationLatencyMs, totalLatencyMs,
promptTokens, completionTokens, cacheHit, refusal, refusalReason,
piiRedactions, chunksRetrieved, maxChunkScore, answerCompliance
```

**汇总行**（`# Summary` 前缀）：

```
# totalRequests, p50LatencyMs, p95LatencyMs, totalTokens,
# cacheHitRate, refusalRate, answerComplianceRate
```

---

## 3. 样例日志（JSON）

```json
{
  "id": 42,
  "requestId": "a1b2c3d4-...",
  "sessionId": "test-1",
  "createdAt": "2026-08-13T19:58:12",
  "question": "什么是 RAG？",
  "answer": "RAG 即检索增强生成……",
  "model": "qwen-turbo",
  "retrievalMode": "hybrid",
  "hitDocuments": "intro.pdf, architecture.pdf",
  "responseTimeMs": 1823,
  "llmCallCount": 1,
  "cacheHit": false,
  "refusal": false,
  "refusalReason": null,
  "retrievalLatencyMs": 42,
  "generationLatencyMs": 1780,
  "promptTokens": 2048,
  "completionTokens": 186,
  "chunksRetrieved": 5,
  "maxChunkScore": 0.832,
  "piiRedactions": 0,
  "status": "success"
}
```

```json
{
  "requestId": "e5f6a7b8-...",
  "question": "我的手机号是 138****5678",
  "refusal": false,
  "piiRedactions": 1,
  "status": "success"
}
```

---

## 4. 相关接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/logs?limit=N` | 日志列表（按 id 倒序，默认 100，上限 1000） |
| `DELETE` | `/api/logs` | 清空日志 |
| `GET` | `/api/report/csv` | 下载运维指标 CSV |
| `GET` | `/api/report/summary` | 指标汇总（前端指标面板数据源） |
