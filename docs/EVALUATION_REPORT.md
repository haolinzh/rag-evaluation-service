# 评测报告

本文档说明评测体系的设计与如何生成一份评测报告。评测脚本在 `evaluation/` 目录，一键驱动 `./run_all.sh`。

---

## 1. 测试集设计

`evaluation/questions.json` 包含 **22 道中英双语测试题**，覆盖 6 类题型：

| 题型 | 数量 | 说明 |
|---|---|---|
| factual（事实型） | 6 | 考察知识库检索与忠实回答 |
| comparison（对比型） | 5 | 考察多源上下文综合 |
| explanatory（解释型） | 5 | 考察生成质量 |
| design（设计型） | 2 | 考察结构化输出 |
| safety（安全型） | 2 | 考察安全边界说明 |
| safety_refusal（拒答型） | 2 | 考察拒答行为是否正确 |

中英各 11 题，难度分 basic / intermediate / advanced 三档。

---

## 2. 评测指标

评测采用**规则代理（rule-based proxy）**，非 LLM-as-judge，适合快速回归；生产环境建议升级为 RAGAS 或 LLM 裁判。

| 指标 | 目标 | 说明 |
|---|---|---|
| Faithfulness（忠实度） | ≥ 0.85 | 回答是否基于检索到的上下文 |
| Context Precision（上下文精确度） | ≥ 0.70 | 召回上下文的相关性 |
| Answer Compliance（合规率） | ≥ 90% | 回答格式与规范符合度 |
| Refusal Appropriateness（拒答恰当性） | — | 拒答行为是否正确 |
| Style Consistency（风格一致性） | — | 风格是否稳定专业 |

---

## 3. 运行方式

```bash
cd evaluation
./run_all.sh
```

脚本流程：健康检查 → 以 `hybrid` 模式跑 22 题 → 以 `vector` 模式跑 22 题 → 输出对比报告。

产物：

- `results_hybrid.json` / `results_vector.json` — 明细（每题指标 + 答案 + 延迟）
- `final_comparison.csv` — 两种模式的对比表

---

## 4. 报告模板

一份完整评测报告应包含以下部分（可直接从脚本产物汇总）：

```markdown
# RAG 评测报告
- 日期: 2026-08-13
- 测试集: 22 题（中英各 11）
- 基础设施: PostgreSQL 16 + pgvector / ES 8.13.4 / Redis 7
- 模型: qwen-plus + text-embedding-v3 (+ qwen3-rerank)

## 汇总结果
| 指标 | vector | hybrid | 目标 |
|---|---|---|---|
| Faithfulness | 0.xx | 0.xx | ≥ 0.85 |
| Context Precision | 0.xx | 0.xx | ≥ 0.70 |
| Answer Compliance | 0.xx | 0.xx | ≥ 90% |
| Refusal Appropriateness | 1.00 | 1.00 | — |
| Style Consistency | 0.xx | 0.xx | — |
| p50 延迟 (ms) | xx | xx | — |
| p95 延迟 (ms) | xx | xx | — |

## 结论
- hybrid 相对 vector 在 Context Precision 上提升 x%
- 2 道 safety_refusal 均正确拒答
- （待改进项……）
```

---

## 5. 已知限制

1. 当前指标为规则代理，非 LLM 裁判，分数偏乐观；忠实度 `min=0.5` 有下限保护。
2. 评测依赖本地已入库的语料，语料不足会导致 Context Precision 偏低。
3. `hybrid-rerank` 模式需额外消耗精排 API，建议单独对比。
