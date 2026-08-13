package com.rag.eval.controller;

import com.rag.eval.exception.DuplicateDocumentException;
import com.rag.eval.model.ChunkConfig;
import com.rag.eval.model.ChunkPreview;
import com.rag.eval.model.DocumentMeta;
import com.rag.eval.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/upload")
    public ResponseEntity<DocumentMeta> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "splitMode", defaultValue = ChunkConfig.MODE_SIZE) String splitMode,
            @RequestParam(value = "chunkSize", defaultValue = "500") int chunkSize,
            @RequestParam(value = "delimiter", defaultValue = "") String delimiter,
            @RequestParam(value = "overlap", defaultValue = "50") int overlap) throws Exception {
        ChunkConfig config = new ChunkConfig(splitMode, chunkSize, delimiter, overlap);
        return ResponseEntity.ok(documentService.ingest(file, config));
    }

    @GetMapping
    public ResponseEntity<List<DocumentMeta>> listAll() {
        return ResponseEntity.ok(documentService.listAll());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long id) {
        documentService.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Deleted"));
    }

    @GetMapping("/{id}/chunks")
    public ResponseEntity<List<ChunkPreview>> chunks(@PathVariable Long id) {
        return ResponseEntity.ok(documentService.getChunkPreviews(id));
    }

    @ExceptionHandler(DuplicateDocumentException.class)
    public ResponseEntity<Map<String, String>> handleDuplicate(DuplicateDocumentException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }
}
