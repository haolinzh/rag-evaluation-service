package com.rag.eval.service;

import com.rag.eval.model.ChunkConfig;
import com.rag.eval.model.ChunkPreview;
import com.rag.eval.model.DocumentMeta;
import com.rag.eval.repository.DocumentMetaRepo;
import com.rag.eval.repository.VectorChunkRepo;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
public class DocumentService {

    private final DocumentParserService parser;
    private final IndexBuilder indexBuilder;
    private final DocumentMetaRepo docRepo;
    private final VectorChunkRepo vectorChunkRepo;
    private final ElasticsearchService esService;
    private final SemanticCacheService cacheService;

    public DocumentService(DocumentParserService parser, IndexBuilder indexBuilder,
                           DocumentMetaRepo docRepo, VectorChunkRepo vectorChunkRepo,
                           ElasticsearchService esService, SemanticCacheService cacheService) {
        this.parser = parser;
        this.indexBuilder = indexBuilder;
        this.docRepo = docRepo;
        this.vectorChunkRepo = vectorChunkRepo;
        this.esService = esService;
        this.cacheService = cacheService;
    }

    public DocumentMeta ingest(MultipartFile file) throws Exception {
        return ingest(file, ChunkConfig.defaults());
    }

    public DocumentMeta ingest(MultipartFile file, ChunkConfig config) throws Exception {
        String fileName = file.getOriginalFilename();
        // Parse first: a corrupt upload fails before the existing version is removed.
        DocumentParserService.ParsedDocument parsed = parser.parse(file.getInputStream(), fileName);
        List<ChunkData> chunks = parser.splitAndEnrich(parsed.text(), fileName, parsed.sourceType(), config);
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setChunkIndex(i);
        }

        // Same-name re-upload replaces the previous version.
        docRepo.findByFileName(fileName).ifPresent(this::deleteExisting);

        indexBuilder.buildIndex(chunks);

        DocumentMeta meta = new DocumentMeta();
        meta.setFileName(fileName);
        meta.setFileSize(file.getSize());
        meta.setChunkCount(chunks.size());
        meta.setSplitMode(config.splitMode());
        meta.setChunkSize(config.chunkSize());
        meta.setOverlap(config.overlap());
        meta.setDelimiter(config.isDelimiterMode() ? config.delimiter() : null);
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
        docRepo.findById(id).ifPresent(this::deleteExisting);
    }

    private void deleteExisting(DocumentMeta meta) {
        vectorChunkRepo.deleteByFileName(meta.getFileName());
        esService.deleteByFileName(meta.getFileName());
        docRepo.delete(meta);
        cacheService.clear();
    }
}
