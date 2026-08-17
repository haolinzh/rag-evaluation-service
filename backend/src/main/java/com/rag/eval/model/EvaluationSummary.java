package com.rag.eval.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class EvaluationSummary {
    private String mode;
    private int totalQuestions;
    private int answeredQuestions;
    private double avgFaithfulness;
    private double avgContextPrecision;
    private double avgAnswerCompliance;
    private double avgRefusalAppropriate;
    private double avgStyleConsistent;
    private double p50LatencyMs;
    private double p95LatencyMs;
    private double avgLatencyMs;
}
