package com.rag.eval.service;

import com.rag.eval.model.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SafetyServiceTest {

    private static SafetyService safetyService(double minSim, boolean outOfScope, double threshold,
                                              List<String> keywords) {
        ConfigService config = mock(ConfigService.class);
        when(config.getDouble("safety.min-similarity", 0.4)).thenReturn(minSim);
        when(config.getBool("safety.enable-out-of-scope-check", true)).thenReturn(outOfScope);
        when(config.getDouble("safety.out-of-scope-threshold", 0.55)).thenReturn(threshold);
        when(config.getList("safety.forbidden-keywords")).thenReturn(keywords);
        return new SafetyService(config);
    }

    private final SafetyService safetyService =
        safetyService(0.7, true, 0.85, List.of("violence", "hate"));

    @Test
    void evaluate_goodQuestion_allow() {
        var chunks = List.of(
            SearchResult.builder().chunkId("A").score(0.9).build()
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
    void evaluate_outOfScope_refuse() {
        // Score between min-similarity (0.7) and out-of-scope threshold (0.85)
        var chunks = List.of(
            SearchResult.builder().chunkId("A").score(0.75).build()
        );
        var result = safetyService.evaluate("How do I bake a sourdough loaf?", chunks);
        assertFalse(result.allowed());
        assertEquals(SafetyService.Decision.REFUSE_OUT_OF_SCOPE, result.decision());
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
