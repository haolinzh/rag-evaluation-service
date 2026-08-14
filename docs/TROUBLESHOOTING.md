# 问题诊断指南

按症状分类的排查路径，附日志/接口定位方法。

---

## 1. 后端启动失败

| 症状 | 排查 |
|---|---|
| `TypeTag :: UNKNOWN` / Lombok 相关报错 | JDK 版本过高。必须用 JDK 17：`export JAVA_HOME=/path/to/temurin-17` 后重新 `mvn spring-boot:run` |
| `MissingProjectException` | 当前目录不在 `backend/`，先 `cd backend` |
| 启动即报 DashScope 401/未配置 | `DASHSCOPE_API_KEY` 环境变量未设置或已过期 |
| `Connection refused` to 5432/9200/6379 | 基础设施未启动：`docker-compose up -d`，`docker-compose ps` 确认三容器 healthy |

---

## 2. 检索/回答质量

| 症状 | 排查 |
|---|---|
| 回答「知识库中暂无相关信息」 | 检查 `retrieval.similarity-threshold` 是否过高；或语料未入库。用 `GET /api/documents` 看文档数与 chunk 数 |
| 拒答偏多（`REFUSE_LOW_CONFIDENCE` / `REFUSE_OUT_OF_SCOPE`） | `safety.min-similarity` / `safety.out-of-scope-threshold` 过高。可临时 `enable-out-of-scope-check: false` 关闭越界闸门对比 |
| 命中文档与问题无关 | 语料 chunk 过大/过小。查看 `GET /api/documents/{id}/chunks` 预览，调整 chunkSize/overlap |
| 来源为空但答案存在 | 命中缓存但缓存是旧版纯文本（无 sources）。清缓存 `POST /api/cache/clear` 后重试 |

---

## 3. 成本/延迟异常

| 症状 | 排查 |
|---|---|
| 延迟突然升高 | 看 `GET /api/report/summary` 的 p95 与 missP95；分离检索/生成耗时（`retrievalLatencyMs` vs `generationLatencyMs`） |
| 精排配额告警 | 临时改用 `hybrid` 模式（不精排），退化为纯 RRF |
| 重复问题仍走生成 | 语义缓存未命中，检查 Redis 连接与 `cache.semantic.similarity-threshold` |

---

## 4. 日志与指标

| 症状 | 排查 |
|---|---|
| 日志面板无数据 | 确认发了请求；`GET /api/logs?limit=10` 直接看返回；检查 PostgreSQL `request_log` 表 |
| CSV 下载为空 | 无任何请求记录时返回 `No data available.`，先发几次问答 |
| 结构化日志无 traceId | 确认走 `logback-spring.xml` 且 MDC 已写入（`ChatService.ask` 开头 `MDC.put("traceId", ...)`） |

---

## 5. 常用定位命令

```bash
# 基础设施健康
docker-compose ps

# 文档入库情况
curl -s localhost:8080/api/documents

# 单条日志
curl -s 'localhost:8080/api/logs?limit=5'

# 指标汇总
curl -s localhost:8080/api/report/summary

# 单次问答（观察 retrievalMode / refusal / sources）
curl -s -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"什么是RAG？","sessionId":"debug-1","mode":"hybrid"}'
```
