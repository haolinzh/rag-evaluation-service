package com.rag.eval.service;

import com.rag.eval.model.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SafetyServiceTest {

    private final SafetyService safetyService = new SafetyService(0.7, List.of("violence", "hate"));

    @Test
    void evaluate_goodQuestion_allow() {
        var chunks = List.of(
            SearchResult.builder().chunkId("A").score(0.85).build()
        );
        var result = safetyService.evaluate("What is RAG?", chunks);
        assertTrue(result.allowed());
    }

    @Test
    void evaluate_lowScoreChunks_refuse() {
        var chunks = List.of(
            SearchResult.builder().chunkId("A").score(0.3).build()
        );
        var result = safetyService.evaluate("any question", chunks);
        assertFalse(result.allowed());
        assertEquals(SafetyService.Decision.REFUSE_LOW_CONFIDENCE, result.decision());
    }

    @Test
    void evaluate_emptyChunks_refuse() {
        var result = safetyService.evaluate("any question", List.of());
        assertFalse(result.allowed());
    }

    @Test
    void evaluate_forbiddenKeyword_refuse() {
        var chunks = List.of(
            SearchResult.builder().chunkId("A").score(0.9).build()
        );
        var result = safetyService.evaluate("How to commit violence?", chunks);
        assertFalse(result.allowed());
        assertEquals(SafetyService.Decision.REFUSE_SAFETY_VIOLATION, result.decision());
    }
}
