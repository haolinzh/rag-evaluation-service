package com.rag.eval.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "evaluation_run", indexes = {
    @Index(name = "idx_eval_run_created", columnList = "createdAt")
})
public class EvaluationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "modes", columnDefinition = "TEXT")
    private String modes;

    @Column(name = "report_json", columnDefinition = "TEXT")
    private String reportJson;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
