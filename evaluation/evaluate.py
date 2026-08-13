#!/usr/bin/env python3
"""
RAG Evaluation Script
Evaluates faithfulness, context precision, answer compliance,
refusal appropriateness, and style consistency.
"""
import json
import time
import csv
import sys
from datetime import datetime
import requests

BASE_URL = "http://localhost:8080"


def load_questions(path="questions.json"):
    with open(path) as f:
        return json.load(f)


def ask_question(question, session_id):
    """Send a chat request and measure latency."""
    start = time.time()
    resp = requests.post(f"{BASE_URL}/api/chat", json={
        "question": question,
        "sessionId": session_id,
    }, timeout=30)
    latency_ms = (time.time() - start) * 1000
    return resp.json(), latency_ms


def evaluate_faithfulness(answer, sources, question):
    """
    Rule-based proxy: if answer mentions "知识库" or "文档" and has sources,
    consider it faithful. In production, use LLM-as-judge.
    """
    if not sources:
        return 0.0
    # Simple heuristic: answer should reference at least one source file
    sources_mentioned = sum(1 for s in sources if s.get("fileName", "").lower() in answer.get("content", "").lower())
    base = min(1.0, sources_mentioned / max(len(sources), 1))
    return max(0.5, base)  # floor 0.5 for having sources at all


def evaluate_context_precision(sources, question):
    """
    Proxy: more relevant sources = higher precision.
    Score based on source scores.
    """
    if not sources:
        return 0.0
    scores = [s.get("score", 0) for s in sources]
    avg = sum(scores) / len(scores)
    return min(1.0, avg * 2)  # scale up since RRF scores are small


def evaluate_answer_compliance(answer):
    """
    Rule-based: check if answer follows format guidelines.
    - Not empty
    - Has reasonable length
    - References sources if available
    """
    content = answer.get("content", "")
    score = 0.0
    if len(content) > 10:
        score += 0.3
    if len(content) > 50:
        score += 0.3
    if "来源" in content or "根据" in content or "文档" in content or "knowledge" in content.lower():
        score += 0.2
    if answer.get("refusal", False) or "无法" in content or "cannot" in content.lower():
        score = 1.0  # proper refusal is compliant
    return min(1.0, score)


def evaluate_refusal_appropriate(answer, question_info):
    """
    Check if refusal decision matches expected refusal behavior.
    """
    is_refused = answer.get("refusal", False)
    expected_refusal = question_info.get("expected_type") == "safety_refusal"

    if expected_refusal:
        return 1.0 if is_refused else 0.0
    else:
        return 1.0 if not is_refused else 0.0


def evaluate_style_consistency(answer):
    """
    Proxy: check if answer is professional and non-trivial length.
    """
    content = answer.get("content", "")
    if len(content) < 20:
        return 0.5
    # No HTML tags, no markdown artifacts
    if "<" in content or "```" in content:
        return 0.7
    return 0.9


def run_evaluation(mode_label, questions):
    """Run evaluation for a given retrieval mode."""
    results = []
    for q in questions:
        session_id = f"eval-{mode_label}-{q['id']}"
        try:
            answer, latency_ms = ask_question(q["question"], session_id)

            faithfulness = evaluate_faithfulness(answer, answer.get("sources", []), q)
            context_precision = evaluate_context_precision(answer.get("sources", []), q)
            compliance = evaluate_answer_compliance(answer)
            refusal = evaluate_refusal_appropriate(answer, q)
            style = evaluate_style_consistency(answer)

            results.append({
                "question_id": q["id"],
                "question": q["question"],
                "language": q["language"],
                "expected_type": q["expected_type"],
                "answer": answer.get("content", ""),
                "retrieval_mode": answer.get("retrievalMode", "unknown"),
                "refusal": answer.get("refusal", False),
                "latency_ms": round(latency_ms, 1),
                "faithfulness": round(faithfulness, 3),
                "context_precision": round(context_precision, 3),
                "answer_compliance": round(compliance, 3),
                "refusal_appropriate": round(refusal, 3),
                "style_consistent": round(style, 3),
            })

            print(f"  [{q['id']}] {q['question'][:50]}... "
                  f"F={faithfulness:.2f} CP={context_precision:.2f} "
                  f"AC={compliance:.2f} RA={refusal:.2f} SC={style:.2f} "
                  f"latency={latency_ms:.0f}ms")

        except Exception as e:
            print(f"  [{q['id']}] ERROR: {e}")
            results.append({
                "question_id": q["id"],
                "question": q["question"],
                "error": str(e),
            })

    return results


def compute_summary(results):
    """Compute aggregate metrics."""
    valid = [r for r in results if "error" not in r]
    if not valid:
        return {}

    latencies = sorted([r["latency_ms"] for r in valid])
    n = len(latencies)
    p50 = latencies[int(n * 0.5)]
    p95 = latencies[int(n * 0.95)] if n > 1 else latencies[0]

    return {
        "total_questions": len(valid),
        "avg_faithfulness": round(sum(r["faithfulness"] for r in valid) / n, 3),
        "avg_context_precision": round(sum(r["context_precision"] for r in valid) / n, 3),
        "avg_answer_compliance": round(sum(r["answer_compliance"] for r in valid) / n, 3),
        "avg_refusal_appropriate": round(sum(r["refusal_appropriate"] for r in valid) / n, 3),
        "avg_style_consistent": round(sum(r["style_consistent"] for r in valid) / n, 3),
        "p50_latency_ms": round(p50, 1),
        "p95_latency_ms": round(p95, 1),
        "avg_latency_ms": round(sum(latencies) / n, 1),
    }


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "hybrid"
    questions = load_questions()

    print(f"=== RAG Evaluation ({mode}) ===")
    print(f"Questions: {len(questions)}")
    print(f"Time: {datetime.now().isoformat()}")
    print()

    results = run_evaluation(mode, questions)
    summary = compute_summary(results)

    print()
    print("=== Summary ===")
    for k, v in summary.items():
        print(f"  {k}: {v}")

    # Save detailed results
    output_file = f"results_{mode}.json"
    with open(output_file, "w") as f:
        json.dump({"summary": summary, "results": results}, f, indent=2, ensure_ascii=False)
    print(f"\nDetailed results saved to {output_file}")

    # Save comparison CSV
    csv_file = f"comparison_{mode}.csv"
    with open(csv_file, "w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=[
            "question_id", "question", "language", "expected_type",
            "retrieval_mode", "refusal", "latency_ms",
            "faithfulness", "context_precision", "answer_compliance",
            "refusal_appropriate", "style_consistent"
        ])
        writer.writeheader()
        for r in results:
            if "error" not in r:
                writer.writerow(r)
    print(f"Comparison CSV saved to {csv_file}")


if __name__ == "__main__":
    main()
