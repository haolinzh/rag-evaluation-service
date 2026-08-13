package com.rag.eval.repository;

import com.rag.eval.model.RequestLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestLogRepo extends JpaRepository<RequestLog, Long> {
}
