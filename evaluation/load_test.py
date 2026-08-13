#!/usr/bin/env python3
"""
Concurrency / load test for the RAG chat endpoint.

Fires N requests with C concurrent workers against POST /api/chat and reports
latency percentiles, throughput, and error rate. Useful for verifying the
service behaves under concurrent retrieval + generation load.

Usage:
    python load_test.py                         # defaults below
    python load_test.py --url http://localhost:8080 --concurrency 8 --requests 40
    python load_test.py --mode hybrid --question "什么是 RAG？"
"""
import argparse
import statistics
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests


def send_one(url, question, session_id, mode, timeout):
    start = time.perf_counter()
    try:
        resp = requests.post(
            f"{url}/api/chat",
            json={"question": question, "sessionId": session_id, "mode": mode},
            timeout=timeout,
        )
        latency_ms = (time.perf_counter() - start) * 1000
        ok = resp.status_code == 200 and resp.json().get("content") is not None
        return latency_ms, ok, resp.status_code
    except Exception as e:
        return (time.perf_counter() - start) * 1000, False, type(e).__name__


def percentile(sorted_values, p):
    if not sorted_values:
        return 0.0
    idx = min(len(sorted_values) - 1, int(len(sorted_values) * p))
    return sorted_values[idx]


def main():
    parser = argparse.ArgumentParser(description="RAG chat endpoint load test")
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--requests", type=int, default=40)
    parser.add_argument("--mode", default="hybrid")
    parser.add_argument("--question", default="什么是 RAG？")
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()

    print(f"=== Load Test ===")
    print(f"URL: {args.url}  concurrency={args.concurrency}  requests={args.requests}  mode={args.mode}")
    print(f"Question: {args.question}\n")

    latencies = []
    ok_count = 0
    errors = {}

    start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [
            pool.submit(send_one, args.url, args.question, f"load-{i}", args.mode, args.timeout)
            for i in range(args.requests)
        ]
        for future in as_completed(futures):
            latency_ms, ok, code = future.result()
            latencies.append(latency_ms)
            if ok:
                ok_count += 1
            else:
                errors[code] = errors.get(code, 0) + 1

    elapsed = time.perf_counter() - start
    latencies.sort()

    print("=== Results ===")
    print(f"  total requests:      {args.requests}")
    print(f"  succeeded:           {ok_count}")
    print(f"  failed:              {args.requests - ok_count}")
    if errors:
        print(f"  failure breakdown:   {errors}")
    print(f"  throughput:          {args.requests / elapsed:.2f} req/s")
    print(f"  total wall time:     {elapsed:.2f}s")
    print(f"  latency avg:         {statistics.mean(latencies):.0f} ms")
    print(f"  latency p50:         {percentile(latencies, 0.50):.0f} ms")
    print(f"  latency p95:         {percentile(latencies, 0.95):.0f} ms")
    print(f"  latency p99:         {percentile(latencies, 0.99):.0f} ms")
    print(f"  latency max:         {max(latencies):.0f} ms")


if __name__ == "__main__":
    main()
