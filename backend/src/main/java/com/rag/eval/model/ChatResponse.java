package com.rag.eval.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private String content;
    private String retrievalMode;
    private List<Source> sources;
    private boolean refusal;
    private String refusalReason;
}
