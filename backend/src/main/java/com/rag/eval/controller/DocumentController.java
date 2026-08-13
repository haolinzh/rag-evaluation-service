package com.rag.eval.controller;

import com.rag.eval.model.DocumentMeta;
import com.rag.eval.service.DocumentService;
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
    public ResponseEntity<DocumentMeta> upload(@RequestParam("file") MultipartFile file) throws Exception {
        return ResponseEntity.ok(documentService.ingest(file));
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
}
