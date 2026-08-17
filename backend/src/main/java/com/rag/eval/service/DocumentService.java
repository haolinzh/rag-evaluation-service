package com.rag.eval.service;

import com.rag.eval.model.ChunkConfig;
import com.rag.eval.model.ChunkPreview;
import com.rag.eval.model.DocumentMeta;
import com.rag.eval.repository.DocumentMetaRepo;
import com.rag.eval.repository.VectorChunkRepo;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;

@Service
public class DocumentService {

    private final DocumentParserService parser;
    private final IndexBuilder indexBuilder;
    private final DocumentMetaRepo docRepo;
    private final VectorChunkRepo vectorChunkRepo;
    private final ElasticsearchService esService;
    private final SemanticCacheService cacheService;
    private final FileStorageService fileStorage;

    public DocumentService(DocumentParserService parser, IndexBuilder indexBuilder,
                           DocumentMetaRepo docRepo, VectorChunkRepo vectorChunkRepo,
                           ElasticsearchService esService, SemanticCacheService cacheService,
                           FileStorageService fileStorage) {
        this.parser = parser;
        this.indexBuilder = indexBuilder;
        this.docRepo = docRepo;
        this.vectorChunkRepo = vectorChunkRepo;
        this.esService = esService;
        this.cacheService = cacheService;
        this.fileStorage = fileStorage;
    }

    public record OriginalFile(String fileName, byte[] bytes) {}

    public DocumentMeta ingest(MultipartFile file) throws Exception {
        return ingest(file, ChunkConfig.defaults());
    }

    public DocumentMeta ingest(MultipartFile file, ChunkConfig config) throws Exception {
        return ingestBytes(file.getOriginalFilename(), file.getBytes(), file.getSize(), config);
    }

    public DocumentMeta ingestBytes(String fileName, byte[] bytes, long fileSize, ChunkConfig config) throws Exception {
        // Parse first: a corrupt file fails before the existing version is removed.
        DocumentParserService.ParsedDocument parsed = parser.parse(new ByteArrayInputStream(bytes), fileName);
        List<ChunkData> chunks = parser.splitAndEnrich(parsed.text(), fileName, parsed.sourceType(), config);
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setChunkIndex(i);
        }

        // Same-name re-upload replaces the previous version, keeping the original
        // row (and its created_at) while refreshing updated_at.
        DocumentMeta meta = docRepo.findByFileName(fileName).orElse(null);
        if (meta != null) {
            deleteIndexedData(meta);
        } else {
            meta = new DocumentMeta();
        }

        // Persist the original file before indexing.
        String storedFileName = fileStorage.store(fileName, bytes);

        indexBuilder.buildIndex(chunks);

        meta.setFileName(fileName);
        meta.setFileSize(fileSize);
        meta.setChunkCount(chunks.size());
        meta.setSplitMode(config.splitMode());
        meta.setChunkSize(config.chunkSize());
        meta.setOverlap(config.overlap());
        meta.setDelimiter(config.isDelimiterMode() ? config.delimiter() : null);
        meta.setStoredFileName(storedFileName);
        return docRepo.save(meta);
    }

    public List<DocumentMeta> listAll() {
        return docRepo.findAll();
    }

    public List<ChunkPreview> getChunkPreviews(Long id) {
        return docRepo.findById(id)
            .map(m -> vectorChunkRepo.findPreviewsByFileName(m.getFileName(), 20))
            .orElse(List.of());
    }

    public void deleteById(Long id) {
        docRepo.findById(id).ifPresent(meta -> {
            deleteIndexedData(meta);
            docRepo.delete(meta);
        });
    }

    public Optional<OriginalFile> getOriginal(Long id) {
        return docRepo.findById(id).flatMap(m -> {
            try {
                byte[] bytes = fileStorage.load(m.getStoredFileName());
                return bytes == null ? Optional.empty() : Optional.of(new OriginalFile(m.getFileName(), bytes));
            } catch (Exception e) {
                System.err.println("Failed to load original file: " + e.getMessage());
                return Optional.empty();
            }
        });
    }

    private void deleteIndexedData(DocumentMeta meta) {
        vectorChunkRepo.deleteByFileName(meta.getFileName());
        esService.deleteByFileName(meta.getFileName());
        fileStorage.delete(meta.getStoredFileName());
        cacheService.clear();
    }
}
