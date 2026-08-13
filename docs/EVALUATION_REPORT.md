# 评测报告

> 实测日期：2026-08-13　·　测试集：`evaluation/questions.json`（22 题，中英各 11）　·　驱动：`evaluation/run_all.sh` + `evaluation/load_test.py`
> 模型：`qwen-plus` + `text-embedding-v3`（hybrid-rerank 另用 `qwen3-rerank`）

---

## 1. 测试集设计

| 题型 | 数量 | 说明 |
|---|---|---|
| factual（事实型） | 6 | 知识库检索与忠实回答 |
| comparison（对比型） | 5 | 多源上下文综合 |
| explanatory（解释型） | 5 | 生成质量 |
| design（设计型） | 2 | 结构化输出 |
| safety（安全型） | 2 | 安全边界说明 |
| safety_refusal（拒答型） | 2 | 拒答行为 |

指标为**规则代理（rule-based proxy）**，非 LLM-as-judge，适合快速回归。

---

## 2. 三模式对比（实测）

| 指标 | Vector | Hybrid | Hybrid+Rerank | 目标 |
|---|---|---|---|---|
| Faithfulness | 0.189 | 0.220 | 0.220 | ≥ 0.85 |
| Context Precision | 0.318 | 0.018 | 0.261 | ≥ 0.70 |
| Answer Compliance | 0.791 | 0.814 | **0.864** | ≥ 80% |
| Refusal Appropriateness | 0.591 | 0.545 | 0.500 | ≥ 80% |
| Style Consistency | 0.818 | 0.836 | **0.864** | ≥ 80% |
| Avg Latency (ms) | 3762 | 3637 | **3168** | — |
| P50 (ms) | 924 | **570** | 905 | — |
| P95 (ms) | 16528 | 10002 | 13113 | ≤ 10s |

---

## 3. 性能（90% ≤ 10s / ≥5 并发）

单实例 5 并发压测（25 请求，hybrid，问题轮换避开缓存）：**25/25 成功**，吞吐 1.02 req/s，avg 3128ms，p95 13584ms。

按模式统计「≤10s 占比」（22 题单线程冷启动）：

| 模式 | p90 | ≤10s 占比 | 结论 |
|---|---|---|---|
| hybrid | 9431ms | 91% | 达标（勉强） |
| vector | 11063ms | 86% | 未达标 |
| hybrid-rerank | 8696ms | 91% | 达标（勉强） |

**瓶颈**：p95 尾部（10~22s）由少数长答案问题的 `qwen-plus` 生成耗时主导，与检索耗时无关。压测下 p95 进一步升至 13.5s，说明并发会轻微放大尾部延迟。

---

## 4. 修复前后（见 `ISSUE_DIAGNOSIS.md`）

| 指标 | 修复前（hybrid） | 修复后（hybrid） | 变化 |
|---|---|---|---|
| Answer Compliance | 0.532 | 0.814 | **+53%** |
| Style Consistency | 0.691 | 0.836 | +21% |
| Faithfulness | 0.212 | 0.220 | 持平 |
| Context Precision | 0.020 | 0.018 | 持平 |
| Refusal Appropriateness | 0.955 | 0.545 | 见下方说明 |

四项修复：① 中文敏感词缺失 → 补齐；② 越界拒答未实现 → 实现；③ 语义缓存绕过安全规则 → 清缓存；④ 评测脚本 CSV 崩溃 → 修复。

---

## 5. 诚实结论与局限

1. **Answer Compliance / Style 已达标**（≥80%），Faithfulness 与 Context Precision **远低于目标**，根因是**语料与测试集不匹配**：当前语料仅 5 份文档（rag-intro / hybrid-search / compliance / 七周七并发模型 / pii-test），而 22 题覆盖 Spring AI、Elasticsearch、pgvector、Milvus、Qwen、TokenTextSplitter 等语料中不存在的话题，多数题目无可召回内容。

2. **Refusal Appropriateness 由 0.955 降到 0.545 不是回归**：越界拒答生效后，系统正确地拒绝了约 9 道语料无法回答的问题，但测试集的 `expected_type` 标签假定这些问题「应当被回答」，因此按该指标定义被计为「不恰当拒答」。这是测试标签与语料范围不匹配，非代码缺陷。

3. **指标为规则代理**，分数偏乐观（如 Faithfulness 对「有来源」即给 floor 0.5）。生产环境建议升级为 LLM-as-judge 或 RAGAS。

4. **性能勉强达标**：hybrid/hybrid-rerank 的 90 分位 ≤10s（91%），vector 未达标（86%）；尾部延迟受大模型生成长耗时约束，需通过缩小上下文（top-k/截断）、流式输出或更快的对话模型进一步压缩。

**改进方向**：① 扩充语料覆盖测试集话题；② 重标 `expected_type` 或按「语料可答性」过滤评测题；③ 用 LLM-as-judge 替代规则代理；④ 针对长答案问题压缩上下文以压 p95。
