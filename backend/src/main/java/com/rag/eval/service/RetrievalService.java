package com.rag.eval.service;

import com.rag.eval.model.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static net.logstash.logback.argument.StructuredArguments.entries;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private final ElasticsearchService esService;
    private final VectorSearchService vectorService;
    private final RRFusionService rrfService;
    private final RerankService rerankService;
    private final DashScopeService dashScope;
    private final String mode;
    private final int topK;
    private final int recallMultiplier;
    private final int rerankCandidates;
    private final boolean rerankEnabled;

    public RetrievalService(ElasticsearchService esService,
                            VectorSearchService vectorService,
                            RRFusionService rrfService,
                            RerankService rerankService,
                            DashScopeService dashScope,
                            @Value("${retrieval.mode}") String mode,
                            @Value("${retrieval.top-k}") int topK,
                            @Value("${retrieval.recall-size-multiplier}") int recallMultiplier,
                            @Value("${retrieval.rerank-candidates:20}") int rerankCandidates,
                            @Value("${retrieval.rerank-enabled:true}") boolean rerankEnabled) {
        this.esService = esService;
        this.vectorService = vectorService;
        this.rrfService = rrfService;
        this.rerankService = rerankService;
        this.dashScope = dashScope;
        this.mode = mode;
        this.topK = topK;
        this.recallMultiplier = recallMultiplier;
        this.rerankCandidates = rerankCandidates;
        this.rerankEnabled = rerankEnabled;
    }

    public RetrievalResult retrieve(String query, String requestedMode) {
        String effectiveMode = resolveMode(requestedMode);

        Instant embStart = Instant.now();
        String queryEmb = embedQuery(query);
        long embeddingLatencyMs = Duration.between(embStart, Instant.now()).toMillis();

        if ("vector".equals(effectiveMode)) {
            Instant vectorStart = Instant.now();
            List<SearchResult> results = vectorService.semanticSearch(queryEmb, topK);
            long vectorLatencyMs = Duration.between(vectorStart, Instant.now()).toMillis();
            logRetrieval(effectiveMode, 0, results.size(), 0, embeddingLatencyMs, 0, vectorLatencyMs, 0, results);
            return new RetrievalResult(results, 0, results.size(), 0,
                embeddingLatencyMs, 0, vectorLatencyMs, 0);
        }

        // Hybrid + hybrid-rerank share the parallel keyword + semantic recall
        int recallSize = Math.max(topK * recallMultiplier, 30);
        AtomicLong keywordLatency = new AtomicLong();
        AtomicLong vectorLatency = new AtomicLong();

        CompletableFuture<List<SearchResult>> keywordFuture =
            CompletableFuture.supplyAsync(() -> {
                Instant s = Instant.now();
                List<SearchResult> r = esService.keywordSearch(query, recallSize);
                keywordLatency.set(Duration.between(s, Instant.now()).toMillis());
                return r;
            });
        CompletableFuture<List<SearchResult>> vectorFuture =
            CompletableFuture.supplyAsync(() -> {
                Instant s = Instant.now();
                List<SearchResult> r = vectorService.semanticSearch(queryEmb, recallSize);
                vectorLatency.set(Duration.between(s, Instant.now()).toMillis());
                return r;
            });

        List<SearchResult> keywordResults = keywordFuture.join();
        List<SearchResult> vectorResults = vectorFuture.join();
        int overlap = overlapCount(keywordResults, vectorResults);

        if ("hybrid-rerank".equals(effectiveMode)) {
            if (!rerankEnabled) {
                log.warn("hybrid-rerank requested but rerank is disabled; falling back to RRF topK");
                List<SearchResult> fused = rrfService.fuse(keywordResults, vectorResults, topK);
                logRetrieval(effectiveMode, keywordResults.size(), vectorResults.size(), overlap,
                    embeddingLatencyMs, keywordLatency.get(), vectorLatency.get(), 0, fused);
                return new RetrievalResult(fused, keywordResults.size(), vectorResults.size(), overlap,
                    embeddingLatencyMs, keywordLatency.get(), vectorLatency.get(), 0);
            }
            List<SearchResult> fused = rrfService.fuse(keywordResults, vectorResults, rerankCandidates);
            Instant rerankStart = Instant.now();
            List<SearchResult> reranked = rerankService.rerank(query, fused, topK);
            long rerankLatency = Duration.between(rerankStart, Instant.now()).toMillis();
            logRetrieval(effectiveMode, keywordResults.size(), vectorResults.size(), overlap,
                embeddingLatencyMs, keywordLatency.get(), vectorLatency.get(), rerankLatency, reranked);
            return new RetrievalResult(reranked, keywordResults.size(), vectorResults.size(), overlap,
                embeddingLatencyMs, keywordLatency.get(), vectorLatency.get(), rerankLatency);
        }

        List<SearchResult> fused = rrfService.fuse(keywordResults, vectorResults, topK);
        logRetrieval(effectiveMode, keywordResults.size(), vectorResults.size(), overlap,
            embeddingLatencyMs, keywordLatency.get(), vectorLatency.get(), 0, fused);
        return new RetrievalResult(fused, keywordResults.size(), vectorResults.size(), overlap,
            embeddingLatencyMs, keywordLatency.get(), vectorLatency.get(), 0);
    }

    public record RetrievalResult(List<SearchResult> results,
                                  int keywordCount, int vectorCount, int overlapCount,
                                  long embeddingLatencyMs, long keywordLatencyMs,
                                  long vectorLatencyMs, long rerankLatencyMs) {}


    private int overlapCount(List<SearchResult> keyword, List<SearchResult> vector) {
        Set<String> ids = new HashSet<>();
        for (SearchResult r : keyword) ids.add(r.getChunkId());
        int overlap = 0;
        for (SearchResult r : vector) if (ids.contains(r.getChunkId())) overlap++;
        return overlap;
    }

    private void logRetrieval(String mode, int keywordCount, int vectorCount, int overlap,
                              long embeddingLatencyMs, long keywordLatencyMs, long vectorLatencyMs,
                              long rerankLatencyMs, List<SearchResult> results) {
        double maxScore = results.stream().mapToDouble(SearchResult::getScore).max().orElse(0.0);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event", "retrieval");
        fields.put("mode", mode);
        fields.put("keyword_count", keywordCount);
        fields.put("vector_count", vectorCount);
        fields.put("overlap_count", overlap);
        fields.put("embedding_latency_ms", embeddingLatencyMs);
        fields.put("keyword_latency_ms", keywordLatencyMs);
        fields.put("vector_latency_ms", vectorLatencyMs);
        fields.put("rerank_latency_ms", rerankLatencyMs);
        fields.put("chunks_retrieved", results.size());
        fields.put("max_chunk_score", maxScore);
        log.info("Retrieval completed {}", entries(fields));
    }

    public String resolveMode(String requestedMode) {
        if ("rerank".equalsIgnoreCase(requestedMode)) {
            return "hybrid-rerank";
        }
        if ("vector".equalsIgnoreCase(requestedMode)
                || "hybrid".equalsIgnoreCase(requestedMode)
                || "hybrid-rerank".equalsIgnoreCase(requestedMode)) {
            return requestedMode.toLowerCase();
        }
        return mode;
    }

    public String getMode() {
        return mode;
    }

    private String embedQuery(String query) {
        List<Double> embedding = dashScope.embed(query);
        return DashScopeService.embeddingToString(embedding);
    }
}
