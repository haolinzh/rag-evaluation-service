package com.rag.eval.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class EvaluationQuestionResult {
    private String questionId;
    private String question;
    private String language;
    private String expectedType;
    private String answer;
    private String retrievalMode;
    private boolean refusal;
    private List<Source> sources;
    private double latencyMs;
    private double faithfulness;
    private double contextPrecision;
    private double answerCompliance;
    private double refusalAppropriate;
    private double styleConsistent;
    private String error;
}
