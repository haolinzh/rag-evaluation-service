package com.rag.eval.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class EvaluationRequest {
    private List<String> modes;
    private boolean clearCache = true;
}
