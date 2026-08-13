#!/bin/bash
# One-click RAG evaluation pipeline
# Usage: ./run_all.sh

set -e

echo "=========================================="
echo "  RAG Evaluation Pipeline"
echo "=========================================="

# 1. Check prerequisites
echo "[1/5] Checking prerequisites..."
for cmd in python3 curl; do
    if ! command -v $cmd &>/dev/null; then
        echo "ERROR: $cmd is required but not installed."
        exit 1
    fi
done

# Install Python deps
pip3 install -q -r requirements.txt 2>/dev/null || true

# 2. Check if service is running
echo "[2/5] Checking service health..."
if ! curl -s http://localhost:8080/api/documents > /dev/null 2>&1; then
    echo "ERROR: Backend service not running at http://localhost:8080"
    echo "Start it with: cd backend && mvn spring-boot:run"
    exit 1
fi
echo "  Service is healthy."

# 3. Run evaluation with hybrid mode
echo "[3/5] Evaluating HYBRID mode..."
python3 evaluate.py hybrid

# 4. Run evaluation with vector-only mode
echo "[4/5] Evaluating VECTOR mode..."
python3 evaluate.py vector

# 5. Generate comparison report
echo "[5/5] Generating comparison report..."

python3 -c "
import json, csv

with open('results_hybrid.json') as f:
    hybrid = json.load(f)
with open('results_vector.json') as f:
    vector = json.load(f)

print()
print('=== FINAL COMPARISON ===')
print(f'{\"Metric\":<30} {\"Hybrid\":<12} {\"Vector\":<12} {\"Delta\":<10}')
print('-' * 64)

h = hybrid['summary']
v = vector['summary']

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

for key, label in metrics:
    hv = h.get(key, 0)
    vv = v.get(key, 0)
    delta = hv - vv
    print(f'{label:<30} {hv:<12.3f} {vv:<12.3f} {delta:+.3f}')

print()
print('--- Recommendation ---')
faith_delta = h.get('avg_faithfulness', 0) - v.get('avg_faithfulness', 0)
prec_delta = h.get('avg_context_precision', 0) - v.get('avg_context_precision', 0)
if faith_delta > 0.05 or prec_delta > 0.05:
    print('Hybrid (ES + pgvector + RRF) shows meaningful quality improvement.')
    print(f'Faithfulness improvement: {faith_delta:+.3f}')
    print(f'Context Precision improvement: {prec_delta:+.3f}')
else:
    print('Quality differences are within margin. Consider cost/latency trade-off.')

# Save final comparison CSV
rows = []
for key, label in metrics:
    rows.append({'Metric': label, 'Hybrid': h.get(key, 0), 'Vector': v.get(key, 0)})

with open('final_comparison.csv', 'w', newline='') as f:
    w = csv.DictWriter(f, fieldnames=['Metric', 'Hybrid', 'Vector'])
    w.writeheader()
    w.writerows(rows)
print()
print('Comparison report saved to final_comparison.csv')
"

echo ""
echo "=========================================="
echo "  Evaluation Complete!"
echo "  Results: results_hybrid.json"
echo "  Results: results_vector.json"
echo "  Comparison: final_comparison.csv"
echo "=========================================="
