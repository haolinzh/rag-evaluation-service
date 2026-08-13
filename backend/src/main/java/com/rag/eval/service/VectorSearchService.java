package com.rag.eval.service;

import com.rag.eval.model.SearchResult;
import com.rag.eval.repository.VectorChunkRepo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VectorSearchService {

    private final VectorChunkRepo vectorChunkRepo;
    private final double threshold;

    public VectorSearchService(VectorChunkRepo vectorChunkRepo,
                                @Value("${retrieval.similarity-threshold}") double threshold) {
        this.vectorChunkRepo = vectorChunkRepo;
        this.threshold = threshold;
    }

    public List<SearchResult> semanticSearch(String queryEmbedding, int topK) {
        List<VectorChunkRepo.VectorSearchRow> rows =
            vectorChunkRepo.similaritySearch(queryEmbedding, threshold, topK);

        return rows.stream()
            .map(row -> SearchResult.builder()
                .chunkId(row.chunkId())
                .fileName(row.fileName())
                .chapter(row.chapter())
                .section(row.section())
                .content(row.content())
                .score(row.similarity())
                .source("semantic")
                .sourceDetails(new SearchResult.SourceDetail(null, row.similarity(), null))
                .build())
            .toList();
    }
}
