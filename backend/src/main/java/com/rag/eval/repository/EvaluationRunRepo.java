package com.rag.eval.repository;

import com.rag.eval.model.EvaluationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvaluationRunRepo extends JpaRepository<EvaluationRun, Long> {
    List<EvaluationRun> findAllByOrderByIdDesc();
}
