package com.rag.eval.model;

public record OpsReport(
    long totalRequests,
    long p50LatencyMs,
    long p95LatencyMs,
    long missP50LatencyMs,
    long missP95LatencyMs,
    long totalTokens,
    double cacheHitRate,
    double refusalRate
) {}
