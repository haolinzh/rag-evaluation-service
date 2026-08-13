package com.rag.eval.model;

import lombok.Data;

import java.time.Instant;

@Data
public class OpsMetrics {
    private String requestId;
    private String sessionId;
    private Instant timestamp;
    private String retrievalMode;
    private long retrievalLatencyMs;
    private long generationLatencyMs;
    private long totalLatencyMs;
    private int promptTokens;
    private int completionTokens;
    private boolean cacheHit;
    private boolean refusal;
    private String refusalReason;
    private int piiRedactions;
    private int chunksRetrieved;
    private double maxChunkScore;
}
