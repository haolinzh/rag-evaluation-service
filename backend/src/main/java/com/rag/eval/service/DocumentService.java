package com.rag.eval.service;

import com.rag.eval.exception.DuplicateDocumentException;
import com.rag.eval.model.DocumentMeta;
import com.rag.eval.repository.DocumentMetaRepo;
import com.rag.eval.repository.VectorChunkRepo;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
        String fileName = file.getOriginalFilename();
        if (docRepo.findByFileName(fileName).isPresent()) {
            throw new DuplicateDocumentException("文件已存在: " + fileName);
        }
        String text = parser.parse(file.getInputStream());
        String sourceType = "digital";
        List<ChunkData> chunks = parser.splitAndEnrich(text, fileName, sourceType);
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setChunkIndex(i);
        }

        indexBuilder.buildIndex(chunks);

        DocumentMeta meta = new DocumentMeta();
        meta.setFileName(fileName);
        meta.setFileSize(file.getSize());
        meta.setChunkCount(chunks.size());
        return docRepo.save(meta);
    }

    public List<DocumentMeta> listAll() {
        return docRepo.findAll();
    }

    public void deleteById(Long id) {
        docRepo.findById(id).ifPresent(meta -> {
            vectorChunkRepo.deleteByFileName(meta.getFileName());
            esService.deleteByFileName(meta.getFileName());
            docRepo.delete(meta);
            cacheService.clear();
        });
    }
}
