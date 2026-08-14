package com.rag.eval.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "chat_message", indexes = {
    @Index(name = "idx_session_created", columnList = "sessionId, createdAt")
})
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String sessionId;

    @Column(nullable = false, length = 16)
    private String role; // "user" or "assistant"

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "retrieval_mode", length = 32)
    private String retrievalMode;

    @Column(columnDefinition = "TEXT")
    private String sources;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
