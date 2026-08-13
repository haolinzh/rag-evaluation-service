package com.rag.eval.model;

public record ChunkConfig(
    String splitMode,
    int chunkSize,
    String delimiter,
    int overlap
) {
    public static final String MODE_SIZE = "size";
    public static final String MODE_DELIMITER = "delimiter";
    public static final int DEFAULT_CHUNK_SIZE = 500;
    public static final int DEFAULT_OVERLAP = 50;

    public static ChunkConfig defaults() {
        return new ChunkConfig(MODE_SIZE, DEFAULT_CHUNK_SIZE, "", DEFAULT_OVERLAP);
    }

    public boolean isDelimiterMode() {
        return MODE_DELIMITER.equalsIgnoreCase(splitMode)
            && delimiter != null && !delimiter.isBlank();
    }
}
