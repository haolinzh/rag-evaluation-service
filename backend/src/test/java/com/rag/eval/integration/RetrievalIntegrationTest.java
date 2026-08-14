package com.rag.eval.integration;

import com.rag.eval.model.SearchResult;
import com.rag.eval.service.ConfigService;
import com.rag.eval.service.RRFusionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalIntegrationTest {

    private static RRFusionService rrf() {
        ConfigService config = mock(ConfigService.class);
        when(config.getInt("retrieval.rrf-k", 60)).thenReturn(60);
        return new RRFusionService(config);
    }

    @Test
    void fullRRFPipeline_mergeAndRank() {
        // Simulates a full retrieval pipeline: keyword + vector → RRF fusion

        var rrf = rrf();

        // Keyword search results (simulated)
        var kwResults = List.of(
            result("chunk-1", "intro.pdf", "Spring AI is a framework...", 0.95, "keyword"),
            result("chunk-2", "setup.pdf", "To install Spring AI...", 0.88, "keyword"),
            result("chunk-3", "rag.pdf", "RAG combines retrieval...", 0.75, "keyword")
        );

        // Vector search results (simulated)
        var vecResults = List.of(
            result("chunk-3", "rag.pdf", "RAG combines retrieval...", 0.92, "semantic"),
            result("chunk-1", "intro.pdf", "Spring AI is a framework...", 0.89, "semantic"),
            result("chunk-4", "advanced.pdf", "Advanced RAG techniques...", 0.82, "semantic")
        );

        List<SearchResult> fused = rrf.fuse(kwResults, vecResults, 3);

        assertEquals(3, fused.size());

        // chunk-3: keyword rank=3, vector rank=1 → high RRF
        // chunk-1: keyword rank=1, vector rank=2 → highest RRF
        assertEquals("chunk-1", fused.get(0).getChunkId());

        // All fused results should have source="rrf" and rrfScore in sourceDetails
        for (SearchResult r : fused) {
            assertEquals("rrf", r.getSource());
            assertNotNull(r.getSourceDetails());
            assertNotNull(r.getSourceDetails().getRrfScore());
        }
    }

    @Test
    void partialOverlap_resultsStillRanked() {
        var rrf = rrf();

        var kwResults = List.of(
            result("A", "a.pdf", "content A", 0.9, "keyword")
        );
        var vecResults = List.of(
            result("B", "b.pdf", "content B", 0.9, "semantic")
        );

        List<SearchResult> fused = rrf.fuse(kwResults, vecResults, 2);
        assertEquals(2, fused.size());
        // Both should appear with their RRF scores
        for (SearchResult r : fused) {
            assertTrue(r.getScore() > 0);
        }
    }

    private SearchResult result(String chunkId, String fileName, String content, double score, String source) {
        return SearchResult.builder()
            .chunkId(chunkId)
            .fileName(fileName)
            .content(content)
            .score(score)
            .source(source)
            .sourceDetails(new SearchResult.SourceDetail(
                "keyword".equals(source) ? score : null,
                "semantic".equals(source) ? score : null,
                null))
            .build();
    }
}
