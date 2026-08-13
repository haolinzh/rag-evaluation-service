package com.rag.eval.model;

import lombok.Data;

@Data
public class ChatRequest {
    private String question;
    private String sessionId;
}
