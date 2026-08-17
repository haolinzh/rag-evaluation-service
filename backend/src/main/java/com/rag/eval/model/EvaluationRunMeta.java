package com.rag.eval.model;

import java.time.LocalDateTime;
import java.util.List;

public record EvaluationRunMeta(Long id, LocalDateTime createdAt, List<String> modes) {
}
