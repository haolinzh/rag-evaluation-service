package com.rag.eval.service;

import com.rag.eval.model.SearchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class RetrievalService {

    private final ElasticsearchService esService;
    private final VectorSearchService vectorService;
    private final RRFusionService rrfService;
    private final DashScopeService dashScope;
    private final String mode;
    private final int topK;
    private final int recallMultiplier;

    public RetrievalService(ElasticsearchService esService,
                            VectorSearchService vectorService,
                            RRFusionService rrfService,
                            DashScopeService dashScope,
                            @Value("${retrieval.mode}") String mode,
                            @Value("${retrieval.top-k}") int topK,
                            @Value("${retrieval.recall-size-multiplier}") int recallMultiplier) {
        this.esService = esService;
        this.vectorService = vectorService;
        this.rrfService = rrfService;
        this.dashScope = dashScope;
        this.mode = mode;
        this.topK = topK;
        this.recallMultiplier = recallMultiplier;
    }

    public List<SearchResult> retrieve(String query) {
        if ("vector".equals(mode)) {
            String emb = embedQuery(query);
            return vectorService.semanticSearch(emb, topK);
        }

        // Hybrid mode: parallel keyword + semantic, then RRF
        int recallSize = Math.max(topK * recallMultiplier, 30);
        String queryEmb = embedQuery(query);

        CompletableFuture<List<SearchResult>> keywordFuture =
            CompletableFuture.supplyAsync(() -> esService.keywordSearch(query, recallSize));
        CompletableFuture<List<SearchResult>> vectorFuture =
            CompletableFuture.supplyAsync(() -> vectorService.semanticSearch(queryEmb, recallSize));

        List<SearchResult> keywordResults = keywordFuture.join();
        List<SearchResult> vectorResults = vectorFuture.join();

        return rrfService.fuse(keywordResults, vectorResults, topK);
    }

    public String getMode() {
        return mode;
    }

    private String embedQuery(String query) {
        List<Double> embedding = dashScope.embed(query);
        return DashScopeService.embeddingToString(embedding);
    }
}
