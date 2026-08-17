## 目录

- [界面预览](#界面预览)
- [核心特性](#核心特性)
- [快速开始](#快速开始)
  - [1. 前置条件](#1-前置条件)
  - [2. 配置 API Key](#2-配置-api-key)
  - [3. 一键启动](#3-一键启动)
  - [4. 语料入库](#4-语料入库)
  - [5. 访问前端](#5-访问前端)
- [交付文档](#交付文档)
  - [1. 评测](#1-评测)
  - [2. 运维指标报告](#2-运维指标报告)
  - [3. 请求日志](#3-请求日志)
  - [4. 配置说明](#4-配置说明)
- [技术设计](#技术设计)
  - [1. 技术栈](#1-技术栈)
  - [2. 架构](#2-架构)
  - [3. 项目结构](#3-项目结构)
  - [4. API 接口](#4-api-接口)
  - [5. 检索模式与 RRF](#5-检索模式与-rrf)
  - [6. PDF Chunk 策略](#6-pdf-chunk-策略)

---

## 界面预览

![RAG 知识库问答系统主界面](docs/screenshot.jpg)

**主界面**：三栏布局——左侧文档上传与检索模式切换，中间多轮对话（含来源 chunk 标签、思考过程折叠），右侧运维指标与实时日志。

![一键测评（三模式对比）](docs/screenshot-evaluation.jpg)

**一键测评**：跑 22 道中英测试题，对比 `vector` / `hybrid` / `hybrid-rerank` 三种模式的 5 项质量指标，SSE 实时进度。

![文档管理页](docs/screenshot-documents.jpg)

**文档管理**：查看已入库文档的名称、分块方式与时间，支持删除，检索语料由此管理。

![日志详情](docs/screenshot-log-detail.jpg)

**日志详情**：展开单次请求查看完整问题、生成回答、命中来源与 token/耗时明细，用于定位检索或生成问题。

![系统配置页](docs/screenshot-config.jpg)

**系统配置**：检索参数、对话/向量/精排模型、安全阈值与语义缓存的运行时热更新，持久化到数据库，无需重启。

---

## 核心特性

| 能力 | 说明 |
|---|---|
| **多轮对话** | 基于 PostgreSQL 持久化会话历史，最近 N 轮上下文注入 |
| **混合检索** | ES 关键词 + pgvector 语义，`CompletableFuture` 并行召回，RRF 融合 |
| **检索模式可切换** | `vector` / `hybrid` / `hybrid-rerank` 三种模式，前端或请求参数动态切换，用于评测对比 |
| **安全拒答** | 提示注入防御 → 关键词黑名单 → 相似度阈值 → 越界检测，四级闸门 |
| **PII 脱敏** | 星号中段掩码：身份证 `110101********1234`、手机号 `138****5678`、邮箱 `t***@example.com`（按序，避免手机号误匹配身份证号） |
| **语义缓存** | Redis 缓存归一化问题（答案 + 来源一起缓存），命中直接返回，降低重复调用成本 |
| **思考过程展示** | 对话模型返回 `reasoning_content` 时（如 `deepseek-r1`、`qwen3-235b-a22b-thinking`），前端气泡内可折叠展开「思考过程」 |
| **流式输出** | 思考过程与回答通过 SSE（`/api/chat/stream`）逐 token 流式返回，无需等待完整生成 |
| **请求日志** | 以请求为 entry 持久化：请求 ID、时间、问题、session、模型、模式、命中文档、响应时间、LLM 调用次数、token、脱敏数等 |
| **运维指标** | 每请求采集 p50/p95 延迟、token 用量、缓存命中率、拒答率、答案合规率、脱敏次数 |
| **一键评测** | 前端「测评」页一键跑 22 道中英测试题，对比 hybrid / vector / hybrid-rerank 三模式，SSE 实时进度 + 5 项质量指标对比 |
| **评测结果持久化** | 每次评测报告持久化到 PostgreSQL（`evaluation_run` 表），进入测评页可回看任意历史测评 |
| **语料自动入库** | 测评开始前自动检查 8 份 case study 语料，缺失的自动解析/分块/向量化并写入 ES + pgvector，无需手动上传 |
| **运行时配置** | 检索参数、模型选择、安全阈值、语义缓存可通过「系统配置」页热更新，持久化到 `system_config` 表，无需重启 |

---

## 快速开始

### 1. 前置条件

- Docker Desktop（或 Docker Engine + Compose）
- 一个百炼 DashScope API Key（[申请地址](https://bailian.console.aliyun.com/)）

### 2. 配置 API Key

支持两种方式，二选一：

**方式 A：环境变量（本地部署）**

```bash
cp .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY=sk-xxxx
```

> `.env` 已被 `.gitignore` 忽略，不会提交到仓库。

**方式 B：系统配置页（面向「分发出去的使用者」，免改文件）**

启动后浏览器打开 `http://localhost:3000` → 「系统配置」→「API Key」卡片，粘贴 Key 点「保存 Key」即时生效；点「清除」可回退到环境变量。

- 两种方式均持久化；**UI 配置写入 `system_config` 表（`dashscope.api-key`），优先级高于环境变量**，清除后回退到 `.env` 的 `DASHSCOPE_API_KEY`。
- 出于安全，接口只回显脱敏尾号（如 `sk-****550e`），永不回显完整 Key。

### 3. 一键启动

```bash
cd rag-evaluation-service
docker-compose up -d --build
```

一次启动五个容器：PostgreSQL (5432)、Elasticsearch (9200)、Redis (6379)、后端 (8080) 与前端 (3000)。后端镜像内置 `tesseract-ocr` + 中文语言包（`chi_sim`），扫描版 PDF 会自动 OCR 入库；前端镜像内置 nginx，托管构建产物并反代 `/api` 到后端。宿主机只需 Docker，无需安装 JDK/Node/tesseract。首次启动会自动执行 `init-db.sql` 创建 `vector_chunks` 表与 IVF-Flat 索引。

验证健康状态：

```bash
docker-compose ps
```

### 4. 语料入库

两种方式：

- **手动上传**：左侧「文档上传」按钮上传文件，支持 PDF / DOCX / TXT；数字原生 PDF 走文本提取，扫描版 PDF 自动 OCR，均标记 `source_type`。
- **测评前自动入库**：`test-docs/` 目录预置 8 份 case study 语料，通过 `docker-compose` 只读挂载到后端容器（`/data/corpus`）。点击「测评」时，后端会逐条检查这 8 份语料是否已入库，缺失的自动解析/分块/向量化并写入 ES + pgvector，无需手动上传。

### 5. 访问前端

浏览器打开 `http://localhost:3000`。前端已容器化（nginx 托管静态产物 + 反代 `/api` 到后端），无需单独启动。

---

## 交付文档

| 文档 | 说明 |
|---|---|
| [成本估算与模型选型](docs/COST_ESTIMATION.md) | DashScope 三模型选型理由与单次/月度成本估算 |
| [日志字段字典](docs/LOG_FIELD_DICTIONARY.md) | `request_log` 表与指标 CSV 逐字段说明 + 样例 |
| [评测报告](docs/EVALUATION_REPORT.md) | 22 题测试集、5 项指标、三模式对比实测 |
| [问题诊断报告](docs/ISSUE_DIAGNOSIS.md) | 5 个已修复问题的证据 + 前后量化对比 |
| [问题诊断指南](docs/TROUBLESHOOTING.md) | 按症状排查 + 定位命令 |

### 1. 评测

评测用于**对比不同检索模式的效果**（`hybrid` vs `vector` vs `hybrid-rerank`），回答「哪种召回策略更好」这一 case study 的核心问题。评测已内置为前端「测评」页 + 后端 `EvaluationService`（指标算法与 `evaluation/evaluate.py` 一致，已迁移至 Java），不再依赖独立 Python 脚本。

#### 一、怎么跑

1. 按「快速开始」启动服务。
2. 浏览器打开 `http://localhost:3000`，点击右上角「测评」进入测评页。
3. 勾选要对比的检索模式，点击「开始测评」。

后端 `POST /api/evaluation/run` 以 SSE 流式返回进度（`start` / `mode_start` / `question_start` / `question_done` / `mode_done` / `done`），前端实时显示完成进度与逐题结果。**测评开始前会自动检查 8 份语料，缺失的先行入库**（`ingest_*` 事件），无需手动上传。

#### 二、测试集

22 道中英双语题（`evaluation-questions.json`），全部对齐到**实际已入库的 8 份语料**，避免「语料无此话题」导致的空召回：

| 类型 | 数量 | 说明 |
|---|---|---|
| `factual`（事实型） | 10 | 知识库检索与忠实回答 |
| `explanatory`（解释型） | 8 | 生成质量 |
| `comparison`（对比型） | 2 | 多源上下文综合 |
| `safety_refusal`（拒答型） | 2 | 拒答行为（银行卡/炸弹） |

#### 三、怎么打分

后端对每道题调用 `POST /api/chat`，拿到回答与来源后计算 5 项指标：

| 指标 | 计算方式 |
|---|---|
| **Faithfulness**（忠实度） | 语义代理：回答与最匹配来源 chunk 的余弦相似度（按 0.80 释义上限归一）；无来源/拒答 → 0 |
| **Context Precision**（上下文精确度） | 语义代理：RAGAS 式 AP，chunk 与问题相似度 ≥0.45 判相关 |
| **Answer Compliance**（答案合规率） | 规则代理：回答长度 >20 / >60 各 +0.3；含引用标记 +0.2；markdown 结构化 +0.2；拒答记 1.0 |
| **Refusal Appropriateness**（拒答恰当性） | 比对「实际是否拒答」与 `expected_type`：`safety_refusal` 题拒答得 1、不拒答得 0；其余题反之 |
| **Style Consistency**（风格一致性） | 回答过短（<20 字）0.5；含 HTML/代码块反引号 0.7；否则 0.9 |

同时记录每题 `latency_ms`，汇总出 avg / p50 / p95 延迟。若未配置 `DASHSCOPE_API_KEY`，语义代理退化为字符 bigram 词法重叠。

#### 四、结果与历史

每次评测的完整报告（三模式汇总 + 逐题明细）持久化到 PostgreSQL `evaluation_run` 表。进入测评页自动加载历史列表（时间倒序），顶部下拉可回看任意一次测评的对比表与逐题明细，刷新/重进页面结果不丢失。

实测三模式对比数据见 [docs/EVALUATION_REPORT.md](docs/EVALUATION_REPORT.md)。

### 2. 运维指标报告

后端采集每请求指标，通过 CSV 接口导出：

```bash
curl -O localhost:8080/api/report/csv
```

CSV 包含逐请求明细（检索/生成/总延迟、prompt/completion token、缓存命中、拒答、脱敏次数、chunk 数、最高相似度、答案合规分）与汇总行（总请求数、p50/p95 延迟、缓存命中率、拒答率、答案合规率）。

### 3. 请求日志

后端将每次问答请求以「请求」为 entry 持久化到 PostgreSQL（`request_log` 表），字段包括：请求 ID、时间、问题、回答、session、模型、检索模式、命中文档、总/检索/生成延迟、LLM 调用次数、prompt/completion token、缓存命中、拒答及原因、召回 chunk 数、最高相似度、PII 脱敏数、状态（`success` / `refused` / `error`）。

前端提供两处查看入口：

- 主页右侧「日志」面板：每 5 秒自动刷新，显示最近请求概览；
- 「日志详情」独立页：全量表格 + 可展开行查看完整字段，支持刷新与清空。

接口：`GET /api/logs?limit=N`（默认 100，上限 1000）、`DELETE /api/logs`。

### 4. 配置说明

检索参数、模型选择、安全阈值、语义缓存开关均支持在运行时通过前端「系统配置」页（`GET/PUT /api/config`）热更新，持久化到 `system_config` 表，无需重启。以下基础设施连接与 API Key 通过环境变量覆盖（见 `application.yml`）：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `DASHSCOPE_API_KEY` | — | 百炼 API Key（必填，也可在「系统配置」页 UI 配置） |
| `DB_HOST` / `DB_USER` / `DB_PASSWORD` | `localhost` / `rag` / `rag123` | PostgreSQL 连接 |
| `ES_HOST` / `ES_PORT` | `localhost` / `9200` | Elasticsearch 连接 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 连接 |

**API Key 初始化优先级**（`dashscope.api-key`）：`system_config` 表（UI 配置） > 环境变量 `DASHSCOPE_API_KEY` > 无。可通过 `PUT /api/config/apikey` 写入（`{"apiKey":"sk-..."}`）或传空值清除（回退环境变量）；`GET /api/config` 仅返回脱敏尾号 `apiKeyMasked`，不回显完整 Key，避免泄露。

---

## 技术设计

### 1. 技术栈

| 层 | 技术 |
|---|---|
| 后端框架 | Spring Boot 3.4.1 (Java 17) |
| 大模型 | 阿里云百炼 DashScope：`qwen-turbo` (对话) + `text-embedding-v3` (向量) + `qwen3-rerank` (精排)；对话可切换 `qwen-plus` / `qwen-max` / `deepseek-r1` 等模型 |
| 关键词检索 | Elasticsearch 8.13.4 |
| 向量数据库 | PostgreSQL 16 + pgvector (cosine `<=>` 操作符) |
| 缓存 | Redis 7 |
| 文档解析 | Apache Tika 3.1.0 (PDF/DOCX/TXT，含 OCR 扫描件) |
| 前端 | React 18 + TypeScript + Vite + Ant Design 5 + react-resizable-panels（可拖动分栏） |
| 评测 | 后端 Java（`EvaluationService`，语义代理 + 规则代理，SSE 流式，结果持久化） |

### 2. 架构

```
                         ┌─────────────────────────────────────────┐
                         │            Frontend (React + AntD)      │
                         │  文档上传 │ 多轮对话 │ 运维指标面板      │
                         └──────────────────────┬──────────────────┘
                                                │ POST /api/chat
                                                ▼
                         ┌─────────────────────────────────────────┐
                         │            ChatController                │
                         └──────────────────────┬──────────────────┘
                                                ▼
                         ┌─────────────────────────────────────────┐
                         │              ChatService                 │
                         │                                         │
                         │  1. 加载历史 (PostgreSQL, 最近 N 轮)     │
                         │  2. RetrievalService.retrieve(query)     │
                         │       ├─ vector:        VectorSearch     │
                         │       ├─ hybrid:        ES+Vector ──▶ RRF│
                         │       └─ hybrid-rerank: RRF ──▶ Rerank   │
                         │  3. SafetyService.evaluate()  允许/拒答  │
                         │  4. SemanticCacheService.lookup()        │
                         │  5. DashScope (qwen-turbo) 生成          │
                         │  6. PIIRedactionService.redact()         │
                         │  7. 保存历史 + 采集指标 + 写请求日志     │
                         └───────┬──────────┬──────────┬───────────┘
                                 │          │          │
                        ┌────────▼───┐ ┌────▼─────┐ ┌──▼────────┐
                        │ PostgreSQL │ │Elasticse.│ │   Redis   │
                        │  pgvector  │ │  keyword │ │sem. cache │
                        └────────────┘ └──────────┘ └───────────┘

         入库流程: 前端「文档上传」→ Tika 解析 → 分块 → DashScope embedding
                     → ES 索引 + pgvector 向量插入

         评测流程: 前端「测评」→ POST /api/evaluation/run (SSE)
                     → 语料自动入库检查 → 逐题调用 ChatService → 指标打分
                     → 结果持久化到 evaluation_run 表
```

**RRF 融合公式：**

```
RRF_score(d) = Σ 1 / (k + rank_i(d))

其中 k = 60 (默认)，rank_i(d) 为文档 d 在第 i 个结果列表中的 1-based 排名。
```

同时出现在 ES 与向量结果前列的 chunk 得分自然放大；只出现在单一列表的 chunk 仍会保留贡献。确定性、零额外 API 成本、零额外延迟。

### 3. 项目结构

```
rag-evaluation-service/
├── docker-compose.yml              # PostgreSQL(pgvector) + ES + Redis + 后端 + 前端
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/rag/eval/
│       │   ├── RAGApplication.java
│       │   ├── config/             # WebConfig / ES / Redis / pgvector
│       │   ├── controller/         # Chat / Document / Report / Log / Cache / Config / Evaluation
│       │   ├── model/              # DTO + JPA 实体（含 RequestLog）
│       │   ├── repository/         # JPA + JDBC(pgvector 原生 SQL)
│       │   ├── service/            # 检索/重排/安全/脱敏/缓存/指标/报告/评测/语料
│       │   └── pipeline/           # 入库管道（解析/分块/索引）
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── application-vector.yml
│       │   ├── application-hybrid.yml
│       │   └── init-db.sql         # pgvector 扩展 + 表结构
│       └── test/java/.../          # RRF / Safety / PII 单测 + 集成
├── frontend/                       # React 18 + TS + Vite + AntD
│   └── src/
│       ├── App.tsx                 # 三栏可拖动 + 响应式布局
│       └── components/
│           ├── DocumentPanel.tsx    # 上传（chunk 配置）+ 检索模式切换
│           ├── DocumentManagement.tsx # 文档管理页（chunk 预览）
│           ├── ChatPanel.tsx        # 多轮对话 + 来源展示
│           ├── ConfigPage.tsx       # 系统配置页（检索/模型/安全/缓存热更新）
│           ├── MetricsPanel.tsx     # 指标面板 + CSV 下载 + 清缓存
│           ├── LogPanel.tsx         # 主页日志（自动刷新）
│           ├── LogManagement.tsx    # 日志管理页（全量明细）
│           └── EvaluationPage.tsx   # 一键测评页（三模式对比 + 历史回看）
├── test-docs/                      # 8 份 case study 语料（测评前自动入库的源目录）
└── evaluation/                     # 历史离线评测脚本（已被内置 UI 评测取代）
    ├── questions.json              # 22 道中英测试题
    ├── evaluate.py                 # 评测脚本（5 项质量指标）
    └── run_all.sh                  # 一键评测驱动
```

### 4. API 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/chat` | 多轮问答，请求体 `{"question": "...", "sessionId": "...", "mode": "hybrid"}` |
| `POST` | `/api/chat/stream` | 流式问答（SSE，逐 token 返回 `thinking`/`content`/`done` 事件） |
| `GET` | `/api/chat/history/{sessionId}` | 查询会话历史 |
| `DELETE` | `/api/chat/history/{sessionId}` | 删除会话历史 |
| `POST` | `/api/documents/upload` | 上传文档 (multipart，可带 `splitMode`/`chunkSize`/`overlap`/`delimiter` 参数) |
| `GET` | `/api/documents` | 文档列表 |
| `DELETE` | `/api/documents/{id}` | 删除文档 |
| `GET` | `/api/documents/{id}/chunks` | 文档 chunk 预览 |
| `GET` | `/api/logs?limit=100` | 请求日志列表（按 id 倒序） |
| `DELETE` | `/api/logs` | 清空请求日志 |
| `POST` | `/api/cache/clear` | 清空语义缓存 |
| `GET` | `/api/report/csv` | 下载运维指标 CSV |
| `GET` | `/api/report/summary` | 运维指标汇总（JSON，主页指标面板轮询） |
| `GET` | `/api/config` | 读取运行时配置（检索/模型/安全/缓存） |
| `PUT` | `/api/config` | 更新运行时配置，热更新无需重启 |
| `PUT` | `/api/config/mode` | 快速切换检索模式 |
| `PUT` | `/api/config/apikey` | 设置/清除 API Key（`{"apiKey":"sk-..."}`，空值清除并回退环境变量） |
| `GET` | `/api/evaluation/questions` | 读取评测测试集（22 题） |
| `POST` | `/api/evaluation/run` | 一键评测（SSE，实时进度 + 逐题结果 + 指标汇总） |
| `GET` | `/api/evaluation/history` | 历史测评列表（按时间倒序） |
| `GET` | `/api/evaluation/history/{id}` | 某次测评的完整报告 |

**问答示例：**

```bash
curl -X POST localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"什么是 RAG？","sessionId":"test-1","mode":"hybrid"}'
```

响应示例：

```json
{
  "content": "RAG 即检索增强生成……",
  "retrievalMode": "hybrid",
  "sources": [
    { "fileName": "intro.pdf", "snippet": "...", "score": 0.0325, "sourceType": "digital" }
  ],
  "refusal": false,
  "refusalReason": null
}
```

### 5. 检索模式与 RRF

三种模式，可在前端「检索模式」下拉中切换（也支持请求体 `mode` 字段指定）：

| 模式 | 行为 |
|---|---|
| `vector` | 仅 pgvector 向量语义检索 |
| `hybrid` | ES 关键词 + pgvector 向量并行召回 → RRF 融合（无重排） |
| `hybrid-rerank` | ES + 向量 → RRF 融合出候选集 → DashScope `qwen3-rerank` 精排取 topK |

关键参数（可在前端「系统配置」页热更新）：

```yaml
retrieval:
  mode: hybrid              # "vector" | "hybrid" | "hybrid-rerank"
  top-k: 5
  rrf-k: 60
  recall-size-multiplier: 3      # 每路召回 = topK * 3
  rerank-candidates: 20          # hybrid-rerank 时 RRF 先保留的候选数
  similarity-threshold: 0.4
```

### 6. PDF Chunk 策略

针对 case study 的三种语料类型分别处理：

| 类型 | 处理方式 |
|---|---|
| **数字原生 PDF/DOCX** | Tika 提取文本 → 章节检测（`^第[一二三四五六七八九十百]+章`）→ 按 500 字符分块、50 字符重叠，携带 `{chapter, section, chunk_index}` 元数据 |
| **扫描版 PDF** | Tika 内置 OCR 提取 → 按页边界切分（无结构化标题）→ 更大分块补偿 OCR 噪音，标记 `source_type="scanned"` |
| **双语混合文档** | 不做翻译，保留原文，靠 `text-embedding-v3` 多语言向量天然跨语言检索 |

chunk 元数据（同时写入 ES `_source` 与 pgvector `vector_chunks` 表）：

```json
{
  "chunk_id": "uuid",
  "file_name": "compliance-guide-v3.pdf",
  "source_type": "digital",
  "language": "mixed",
  "chapter": "第三章",
  "section": "数据安全要求",
  "content": "...",
  "chunk_index": 12,
  "token_count": 480
}
```

分块参数（切分方式 `size`/`delimiter`、chunk 大小、overlap、分隔符）支持在上传时通过接口或前端配置，`DocumentMeta` 持久化记录每次入库的参数；文档管理页可查看每个文档的 chunk 预览（`GET /api/documents/{id}/chunks`）。

---

## License

仅供学习与面试展示用途。
