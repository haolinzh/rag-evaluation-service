package com.rag.eval.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class EvaluationQuestion {
    private String id;
    private String question;
    private String language;
    @JsonProperty("expected_type")
    private String expectedType;
    private String difficulty;
}
