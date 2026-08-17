package com.rag.eval.service;

import com.rag.eval.model.ChunkConfig;
import com.rag.eval.model.DocumentMeta;
import com.rag.eval.repository.DocumentMetaRepo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class CorpusService {

    private static final List<String> CORPUS_FILES = List.of(
        "rag-intro.txt",
        "hybrid-search.txt",
        "compliance.txt",
        "pii-test.txt",
        "七周七并发模型.pdf",
        "china-national-security-bilingual.pdf",
        "guizhou-wetland-regulation-scanned.pdf",
        "阿里巴巴JAVA开发手册.pdf"
    );

    private final DocumentService documentService;
    private final DocumentMetaRepo docRepo;
    private final Path corpusDir;

    public CorpusService(DocumentService documentService,
                         DocumentMetaRepo docRepo,
                         @Value("${corpus.dir:./test-docs}") String corpusDir) {
        this.documentService = documentService;
        this.docRepo = docRepo;
        this.corpusDir = Path.of(corpusDir);
    }

    public void ensureIngested(Consumer<Map<String, Object>> onEvent) {
        List<String> missing = CORPUS_FILES.stream()
            .filter(name -> docRepo.findByFileName(name).isEmpty())
            .toList();
        if (missing.isEmpty()) {
            emit(onEvent, Map.of("type", "ingest_done", "ingested", 0, "skipped", CORPUS_FILES.size()));
            return;
        }

        emit(onEvent, Map.of("type", "ingest_start", "missing", missing, "total", missing.size()));

        int ingested = 0;
        List<String> failed = new ArrayList<>();
        for (String name : missing) {
            emit(onEvent, Map.of("type", "ingesting", "fileName", name));
            try {
                Path path = corpusDir.resolve(name);
                if (!Files.exists(path)) {
                    throw new IllegalStateException("语料文件不存在: " + path);
                }
                byte[] bytes = Files.readAllBytes(path);
                DocumentMeta meta = documentService.ingestBytes(name, bytes, bytes.length, ChunkConfig.defaults());
                ingested++;
                emit(onEvent, Map.of("type", "ingested", "fileName", name, "chunks", meta.getChunkCount()));
            } catch (Exception e) {
                failed.add(name);
                String msg = e.getMessage() == null ? "unknown" : e.getMessage();
                emit(onEvent, Map.of("type", "ingest_error", "fileName", name, "message", msg));
            }
        }

        emit(onEvent, Map.of("type", "ingest_done", "ingested", ingested, "failed", failed));
    }

    private void emit(Consumer<Map<String, Object>> onEvent, Map<String, Object> event) {
        try {
            onEvent.accept(event);
        } catch (Exception ignored) {
            // client disconnected mid-stream
        }
    }
}
