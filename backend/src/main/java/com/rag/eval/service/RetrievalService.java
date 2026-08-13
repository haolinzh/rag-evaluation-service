package com.rag.eval.service;

import com.rag.eval.model.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

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

    public List<SearchResult> retrieve(String query, String requestedMode) {
        String effectiveMode = resolveMode(requestedMode);
        if ("vector".equals(effectiveMode)) {
            String emb = embedQuery(query);
            return vectorService.semanticSearch(emb, topK);
        }

        // Hybrid + hybrid-rerank share the parallel keyword + semantic recall
        int recallSize = Math.max(topK * recallMultiplier, 30);
        String queryEmb = embedQuery(query);

        CompletableFuture<List<SearchResult>> keywordFuture =
            CompletableFuture.supplyAsync(() -> esService.keywordSearch(query, recallSize));
        CompletableFuture<List<SearchResult>> vectorFuture =
            CompletableFuture.supplyAsync(() -> vectorService.semanticSearch(queryEmb, recallSize));

        List<SearchResult> keywordResults = keywordFuture.join();
        List<SearchResult> vectorResults = vectorFuture.join();

        if ("hybrid-rerank".equals(effectiveMode)) {
            if (!rerankEnabled) {
                log.warn("hybrid-rerank requested but rerank is disabled; falling back to RRF topK");
                return rrfService.fuse(keywordResults, vectorResults, topK);
            }
            List<SearchResult> fused = rrfService.fuse(keywordResults, vectorResults, rerankCandidates);
            return rerankService.rerank(query, fused, topK);
        }
        return rrfService.fuse(keywordResults, vectorResults, topK);
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
