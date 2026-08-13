package com.rag.eval.repository;

import com.rag.eval.model.DocumentMeta;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentMetaRepo extends JpaRepository<DocumentMeta, Long> {
}
