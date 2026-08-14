package com.rag.eval.repository;

import com.rag.eval.model.SystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemConfigRepo extends JpaRepository<SystemConfig, String> {
}
