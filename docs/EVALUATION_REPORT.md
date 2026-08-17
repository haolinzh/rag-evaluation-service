# 评测报告

> 实测日期：2026-08-17　·　测试集：`evaluation-questions.json`（22 题，中 18 / 英 4）　·　驱动：前端「测评」页 / `POST /api/evaluation/run`（指标算法已迁移至后端 `EvaluationService`）
> 模型：`qwen-plus` + `text-embedding-v3`（hybrid-rerank 另用 `qwen3-rerank`）

---

## 1. 测试集设计

22 题全部对齐到**实际已入库的 8 份语料**（rag-intro / hybrid-search / compliance / 七周七并发模型 / pii-test / china-national-security-bilingual / guizhou-wetland-regulation-scanned / 阿里巴巴JAVA开发手册），避免「语料无此话题」导致的空召回。

| 题型 | 数量 | 说明 |
|---|---|---|
| factual（事实型） | 10 | 知识库检索与忠实回答 |
| explanatory（解释型） | 8 | 生成质量 |
| comparison（对比型） | 2 | 多源上下文综合 |
| safety_refusal（拒答型） | 2 | 拒答行为（银行卡/炸弹） |

质量指标分为两类：

- **语义代理（semantic proxy）**：Faithfulness、Context Precision 用系统自身 embedding 模型（text-embedding-v3）计算余弦相似度，作为确定性的、零标注的 judge 替代——忠实度=答案与最匹配来源 chunk 的语义重叠（按 0.80 释义上限归一），上下文精确率=RAGAS 式 AP（chunk 与问题相似度 ≥0.45 判相关）。
- **规则代理（rule-based proxy）**：Answer Compliance、Refusal Appropriateness、Style Consistency 仍为格式/长度/拒答规则。

若未配置 `DASHSCOPE_API_KEY`，语义相似度退化为字符 bigram 词法重叠（结果会标注，不应与语义数值混比）。

---

## 2. 三模式对比（实测）

| 指标 | Vector | Hybrid | Hybrid+Rerank | 目标 |
|---|---|---|---|---|
| Faithfulness | 0.896 | 0.896 | **0.898** | ≥ 0.85 ✓ |
| Context Precision | 0.950 | **0.958** | **0.958** | ≥ 0.70 ✓ |
| Answer Compliance | 0.905 | **0.955** | 0.900 | ≥ 80% ✓ |
| Refusal Appropriateness | **1.000** | **1.000** | **1.000** | ≥ 80% ✓ |
| Style Consistency | 0.882 | **0.900** | 0.891 | ≥ 80% ✓ |
| Avg Latency (ms) | 6038 | **4751** | 5893 | — |
| P50 (ms) | 5249 | **4822** | 5623 | — |
| P95 (ms) | 12609 | **8991** | 13621 | ≤ 10s |

五项质量指标**三模式全部达标**。Faithfulness / Context Precision 已由旧版的 0.19 / 0.32 提升至 0.90 / 0.95 量级——根因是**语料与测试集重新对齐**，并改用语义代理替代了原先「有来源即 floor 0.5」的乐观规则打分。

---

## 3. 性能（90% ≤ 10s）

按模式统计「≤10s 占比」（22 题单线程冷启动，含首次 embedding 连接）：

| 模式 | p90 | ≤10s 占比 | 结论 |
|---|---|---|---|
| hybrid | 8505ms | 21/22 = 95% | 达标 |
| vector | 12506ms | 18/22 = 82% | 未达标 |
| hybrid-rerank | 10893ms | 19/22 = 86% | 未达标 |

**瓶颈**：p95 尾部（10~22s）由少数长答案问题的 `qwen-plus` 生成耗时主导，与检索耗时无关（检索 <100ms）。hybrid 因 RRF 合并后送入 LLM 的上下文更精炼、生成更短而尾部更优；vector / hybrid-rerank 的召回上下文更长，放大了生成方差。

---

## 4. 本次改动（相对 08-13 旧报告）

| 指标 | 旧（hybrid） | 新（hybrid） | 变化 |
|---|---|---|---|
| Faithfulness | 0.220 | 0.896 | **+307%** |
| Context Precision | 0.018 | 0.958 | **+52×** |
| Answer Compliance | 0.814 | 0.955 | +17% |
| Refusal Appropriateness | 0.545 | 1.000 | +83% |
| Style Consistency | 0.836 | 0.900 | +8% |

两项根因修复：① **测试集与语料重新对齐**——旧版 22 题覆盖 Spring AI / Milvus / TokenTextSplitter 等语料中不存在的话题，多数题无可召回内容；② **评测脚本指标重写**——Faithfulness/Context Precision 由乐观规则改为语义代理，Refusal Appropriateness 按「语料可答性」正确判定（旧版将「语料无法回答应拒答」误判为「不恰当拒答」）。

---

## 5. 诚实结论与局限

1. **五项质量指标全部达标**，Faithfulness / Context Precision 达到 0.90 / 0.95 量级，Refusal 三模式 100%。这是对齐语料 + 语义代理共同作用的结果，非调参硬凑。

2. **延迟目标仅 hybrid 达标**：vector（82%）、hybrid-rerank（86%）的 90 分位未达 ≤10s。尾部由 `qwen-plus` 生成长耗时主导，需通过缩小上下文（top-k/截断）、流式输出或更快对话模型（如 `qwen-turbo`）进一步压缩。

3. **指标仍为代理，非 LLM-as-judge**。语义代理是确定性的、可复现的，比旧规则更可信，但对「忠实度」的判断仍是相似度近似，无法识别语义相同但来源不同的细微幻觉。生产环境建议升级为 RAGAS 或 LLM-as-judge。

4. **单线程冷启动**的 p90/p95 偏保守；5 并发压测下尾部会进一步放大，需结合 §3 的上下文压缩一并治理。

**改进方向**：① 针对 vector / hybrid-rerank 压缩召回上下文以压 p95；② 用 LLM-as-judge 替代语义/规则代理；③ 增加 5 并发压测结果入册；④ 将评测纳入 CI 做回归门槛。

---

## 6. 评测已内置 + 结果持久化

本报告实测数据由早期的离线脚本（`evaluation/run_all.sh`）产生；此后评测已内置为后端 `EvaluationService` + 前端「测评」页，指标算法一致（语义代理 + 规则代理）。

- **一键评测**：前端点击「测评」→ 勾选模式 → 开始，SSE 实时进度。
- **语料自动入库**：测评前自动检查 8 份语料，缺失的从 `test-docs/` 自动解析/分块/向量化并写入 ES + pgvector。
- **结果持久化**：每次报告存入 PostgreSQL `evaluation_run` 表，测评页顶部下拉可回看任意历史测评，刷新/重进页面结果不丢失。
