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
}
