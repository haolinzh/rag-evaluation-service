#!/bin/bash
# One-click RAG evaluation pipeline
# Usage: ./run_all.sh

set -e

echo "=========================================="
echo "  RAG Evaluation Pipeline"
echo "=========================================="

# 1. Check prerequisites
echo "[1/4] Checking prerequisites..."
for cmd in python3 curl; do
    if ! command -v $cmd &>/dev/null; then
        echo "ERROR: $cmd is required but not installed."
        exit 1
    fi
done

# Install Python deps
pip3 install -q -r requirements.txt 2>/dev/null || true

# 2. Check if service is running
echo "[2/4] Checking service health..."
if ! curl -s http://localhost:8080/api/documents > /dev/null 2>&1; then
    echo "ERROR: Backend service not running at http://localhost:8080"
    echo "Start it with: cd backend && mvn spring-boot:run"
    exit 1
fi
echo "  Service is healthy."

# 3. Run evaluation for all three configurations
MODES=(hybrid vector hybrid-rerank)
echo "[3/4] Running evaluation..."
for mode in "${MODES[@]}"; do
    echo ""
    echo "----- Evaluating ${mode} -----"
    python3 evaluate.py "$mode"
done

# 4. Generate three-way comparison report
echo ""
echo "[4/4] Generating comparison report..."

python3 -c "
import json, csv

MODES = [('hybrid', 'Hybrid'), ('vector', 'Vector'), ('hybrid-rerank', 'Hybrid+Rerank')]
data = {}
for key, _ in MODES:
    with open(f'results_{key}.json') as f:
        data[key] = json.load(f)['summary']

metrics = [
    ('avg_faithfulness', 'Faithfulness'),
    ('avg_context_precision', 'Context Precision'),
    ('avg_answer_compliance', 'Answer Compliance'),
    ('avg_refusal_appropriate', 'Refusal Appropr.'),
    ('avg_style_consistent', 'Style Consistency'),
    ('avg_latency_ms', 'Avg Latency (ms)'),
    ('p50_latency_ms', 'P50 Latency (ms)'),
    ('p95_latency_ms', 'P95 Latency (ms)'),
]

print()
print('=== FINAL COMPARISON ===')
header = f'{\"Metric\":<30}' + ''.join(f'{label:<16}' for _, label in MODES)
print(header)
print('-' * (30 + 16 * len(MODES)))

for key, label in metrics:
    line = f'{label:<30}'
    for mkey, _ in MODES:
        line += f'{data[mkey].get(key, 0):<16.3f}'
    print(line)

# Save CSV
with open('final_comparison.csv', 'w', newline='') as f:
    w = csv.DictWriter(f, fieldnames=['Metric'] + [label for _, label in MODES])
    w.writeheader()
    for key, label in metrics:
        row = {'Metric': label}
        for mkey, mlabel in MODES:
            row[mlabel] = data[mkey].get(key, 0)
        w.writerow(row)

print()
print('--- Conclusions ---')
best = max(MODES, key=lambda x: data[x[0]].get('avg_faithfulness', 0))
fastest = min(MODES, key=lambda x: data[x[0]].get('avg_latency_ms', 1e9))
print(f'Best faithfulness:        {best[1]}  ({data[best[0]].get(\"avg_faithfulness\", 0):.3f})')
print(f'Best context precision:   {max(MODES, key=lambda x: data[x[0]].get(\"avg_context_precision\", 0))[1]}')
print(f'Lowest avg latency:       {fastest[1]}  ({data[fastest[0]].get(\"avg_latency_ms\", 0):.1f} ms)')
print()
print('Comparison report saved to final_comparison.csv')
"

echo ""
echo "=========================================="
echo "  Evaluation Complete!"
echo "  Results: results_hybrid.json"
echo "           results_vector.json"
echo "           results_hybrid-rerank.json"
echo "  Comparison: final_comparison.csv"
echo "=========================================="
