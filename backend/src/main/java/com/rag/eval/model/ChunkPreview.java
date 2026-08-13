package com.rag.eval.model;

public record ChunkPreview(
    int chunkIndex,
    String chapter,
    String section,
    String snippet
) {}
