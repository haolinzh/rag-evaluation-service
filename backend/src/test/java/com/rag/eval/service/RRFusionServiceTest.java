package com.rag.eval.service;

import com.rag.eval.model.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RRFusionServiceTest {

    private final RRFusionService rrfService = new RRFusionService(60);

    @Test
    void fuse_twoLists_returnsTopKResults() {
        var kw = List.of(
            SearchResult.builder().chunkId("A").fileName("doc1.pdf").content("content A").score(0.9).source("keyword").build(),
            SearchResult.builder().chunkId("B").fileName("doc2.pdf").content("content B").score(0.8).source("keyword").build(),
            SearchResult.builder().chunkId("C").fileName("doc3.pdf").content("content C").score(0.7).source("keyword").build()
        );
        var vec = List.of(
            SearchResult.builder().chunkId("B").fileName("doc2.pdf").content("content B").score(0.95).source("semantic").build(),
            SearchResult.builder().chunkId("A").fileName("doc1.pdf").content("content A").score(0.85).source("semantic").build(),
            SearchResult.builder().chunkId("D").fileName("doc4.pdf").content("content D").score(0.80).source("semantic").build()
        );

        List<SearchResult> fused = rrfService.fuse(kw, vec, 3);

        assertEquals(3, fused.size());
        // "A": keyword rank=1, vector rank=2 → RRF = 1/61 + 1/62 ≈ 0.0325
        // "B": keyword rank=2, vector rank=1 → RRF = 1/62 + 1/61 ≈ 0.0325 (tied with A)
        // Ties are broken by insertion order (keyword processed first, A at rank 1)
        assertEquals("A", fused.get(0).getChunkId());
        assertEquals("B", fused.get(1).getChunkId());
        assertEquals("rrf", fused.get(0).getSource());
    }

    @Test
    void fuse_emptyInput_returnsEmpty() {
        List<SearchResult> fused = rrfService.fuse(List.of(), List.of(), 5);
        assertTrue(fused.isEmpty());
    }

    @Test
    void fuse_oneEmptyList_returnsFromOther() {
        var kw = List.of(
            SearchResult.builder().chunkId("A").fileName("f.pdf").content("c").score(0.9).source("keyword").build()
        );
        List<SearchResult> fused = rrfService.fuse(kw, List.of(), 5);
        assertEquals(1, fused.size());
        assertEquals("A", fused.get(0).getChunkId());
    }

    @Test
    void fuse_topKExceedsAvailable_returnsAll() {
        var kw = List.of(
            SearchResult.builder().chunkId("X").fileName("f.pdf").content("c").score(0.5).source("keyword").build()
        );
        List<SearchResult> fused = rrfService.fuse(kw, List.of(), 10);
        assertEquals(1, fused.size());
    }

    @Test
    void rrfK_affectsScore() {
        RRFusionService largeK = new RRFusionService(100);
        var kw = List.of(
            SearchResult.builder().chunkId("A").fileName("f.pdf").content("c").score(0.9).source("keyword").build()
        );
        var fused60 = rrfService.fuse(kw, List.of(), 1);
        var fused100 = largeK.fuse(kw, List.of(), 1);

        // Larger k makes scores smaller
        assertTrue(fused100.get(0).getScore() < fused60.get(0).getScore());
    }
}
