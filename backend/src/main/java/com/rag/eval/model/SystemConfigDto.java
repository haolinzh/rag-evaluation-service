package com.rag.eval.model;

import java.util.List;

public record SystemConfigDto(
    Retrieval retrieval,
    Models models,
    Safety safety,
    Cache cache,
    List<ModelOption> modelOptions,
    int embeddingDimension
) {
    public record Retrieval(String mode, int topK, int recallSizeMultiplier, int rrfK,
                            int rerankCandidates, boolean rerankEnabled, double similarityThreshold) {}

    public record Models(String chat, String embedding, String rerank) {}

    public record Safety(double minSimilarity, boolean enableOutOfScopeCheck,
                         double outOfScopeThreshold, String forbiddenKeywords) {}

    public record Cache(boolean enabled, int ttlSeconds) {}

    public record ModelOption(String group, String id, String label, Integer dimensions) {}
}
