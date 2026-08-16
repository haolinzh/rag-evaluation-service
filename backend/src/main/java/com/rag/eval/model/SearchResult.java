package com.rag.eval.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResult {
    private String chunkId;
    private String fileName;
    private String chapter;
    private String section;
    private String content;
    private double score;
    private String source; // "keyword" | "semantic" | "rrf"
    private SourceDetail sourceDetails;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceDetail {
        private Double keywordScore;
        private Double semanticScore;
        private Double rrfScore;
    }

    /**
     * The 0..1 semantic similarity when available; otherwise the fusion/rank
     * score. This is the single "confidence" signal the safety gate relies on,
     * kept on a consistent scale (unlike the RRF score, which is 1/(k+rank)).
     */
    public double getConfidenceScore() {
        if (sourceDetails != null && sourceDetails.getSemanticScore() != null) {
            return sourceDetails.getSemanticScore();
        }
        return score;
    }
}
