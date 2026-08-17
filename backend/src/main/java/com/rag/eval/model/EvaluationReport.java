package com.rag.eval.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class EvaluationReport {
    private List<String> modes;
    private List<EvaluationSummary> summaries;
    private Map<String, List<EvaluationQuestionResult>> results;
}
