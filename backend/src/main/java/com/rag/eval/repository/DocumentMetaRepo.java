package com.rag.eval.repository;

import com.rag.eval.model.DocumentMeta;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentMetaRepo extends JpaRepository<DocumentMeta, Long> {
    Optional<DocumentMeta> findByFileName(String fileName);
}
