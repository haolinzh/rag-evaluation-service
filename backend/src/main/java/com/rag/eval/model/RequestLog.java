package com.rag.eval.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "request_log", indexes = {
    @Index(name = "idx_reqlog_created", columnList = "createdAt"),
    @Index(name = "idx_reqlog_session", columnList = "sessionId")
})
public class RequestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "TEXT")
    private String answer;

    @Column(length = 64)
    private String model;

    @Column(name = "retrieval_mode", length = 32)
    private String retrievalMode;

    @Column(name = "hit_documents", columnDefinition = "TEXT")
    private String hitDocuments;

    @Column(name = "retrieved_chunks", columnDefinition = "TEXT")
    private String retrievedChunks;

    @Column(name = "rerank_candidates", columnDefinition = "TEXT")
    private String rerankCandidates;

    @Column(columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "response_time_ms")
    private long responseTimeMs;

    @Column(name = "llm_call_count")
    private int llmCallCount;

    @Column(name = "cache_hit")
    private boolean cacheHit;

    @Column(name = "refusal")
    private boolean refusal;

    @Column(name = "refusal_reason", length = 64)
    private String refusalReason;

    @Column(name = "retrieval_latency_ms")
    private long retrievalLatencyMs;

    @Column(name = "generation_latency_ms")
    private long generationLatencyMs;

    @Column(name = "prompt_tokens")
    private int promptTokens;

    @Column(name = "completion_tokens")
    private int completionTokens;

    @Column(name = "chunks_retrieved")
    private int chunksRetrieved;

    @Column(name = "max_chunk_score")
    private double maxChunkScore;

    @Column(name = "pii_redactions")
    private int piiRedactions;

    @Column(name = "keyword_count")
    private int keywordCount;

    @Column(name = "vector_count")
    private int vectorCount;

    @Column(name = "overlap_count")
    private int overlapCount;

    @Column(name = "embedding_latency_ms")
    private long embeddingLatencyMs;

    @Column(name = "keyword_latency_ms")
    private long keywordLatencyMs;

    @Column(name = "vector_latency_ms")
    private long vectorLatencyMs;

    @Column(name = "rerank_latency_ms")
    private long rerankLatencyMs;

    @Column(name = "cache_lookup_latency_ms")
    private long cacheLookupLatencyMs;

    @Column(length = 16)
    private String status; // "success" | "refused" | "error"

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
