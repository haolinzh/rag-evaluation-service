package com.rag.eval.service;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChunkData {
    private String chunkId;
    private String fileName;
    private String sourceType;  // "digital" | "scanned"
    private String language;     // "zh" | "en" | "mixed"
    private String chapter;
    private String section;
    private int chunkIndex;
    private String content;
}
