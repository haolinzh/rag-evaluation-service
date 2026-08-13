package com.rag.eval.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class IndexBuilder {

    private final ElasticsearchClient esClient;
    private final com.rag.eval.repository.VectorChunkRepo vectorChunkRepo;
    private final DashScopeService dashScope;
    private final String esIndexName;

    public IndexBuilder(ElasticsearchClient esClient,
                        com.rag.eval.repository.VectorChunkRepo vectorChunkRepo,
                        DashScopeService dashScope,
                        @Value("${elasticsearch.index-name}") String esIndexName) {
        this.esClient = esClient;
        this.vectorChunkRepo = vectorChunkRepo;
        this.dashScope = dashScope;
        this.esIndexName = esIndexName;
    }

    public void buildIndex(List<ChunkData> chunks) {
        System.out.println("Indexing " + chunks.size() + " chunks...");

        // DashScope text-embedding-v3 rejects batches larger than 10
        int batchSize = 10;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, chunks.size());
            List<ChunkData> batch = chunks.subList(i, end);

            List<String> texts = batch.stream().map(ChunkData::getContent).toList();

            // Get embeddings via DashScope
            List<List<Double>> embeddings = dashScope.embedBatch(texts);

            // Index to ES
            indexToES(batch);

            // Index to pgvector
            for (int j = 0; j < batch.size() && j < embeddings.size(); j++) {
                ChunkData chunk = batch.get(j);
                String embStr = DashScopeService.embeddingToString(embeddings.get(j));
                vectorChunkRepo.insert(
                    chunk.getChunkId(), chunk.getFileName(), chunk.getSourceType(),
                    chunk.getLanguage(), chunk.getChapter(), chunk.getSection(),
                    j, chunk.getContent(), embStr
                );
            }

            System.out.printf("Indexed %d/%d chunks%n", end, chunks.size());
        }

        System.out.println("Indexing complete.");
    }

    private void indexToES(List<ChunkData> batch) {
        try {
            var bulkBuilder = new BulkRequest.Builder();
            for (ChunkData chunk : batch) {
                Map<String, Object> doc = Map.of(
                    "chunk_id", chunk.getChunkId(),
                    "file_name", chunk.getFileName(),
                    "source_type", chunk.getSourceType(),
                    "language", chunk.getLanguage(),
                    "chapter", chunk.getChapter() != null ? chunk.getChapter() : "",
                    "section", chunk.getSection() != null ? chunk.getSection() : "",
                    "content", chunk.getContent()
                );
                bulkBuilder.operations(op -> op
                    .index(idx -> idx
                        .index(esIndexName)
                        .id(chunk.getChunkId())
                        .document(doc)));
            }
            esClient.bulk(bulkBuilder.build());
        } catch (Exception e) {
            System.err.println("ES indexing failed: " + e.getMessage());
        }
    }
}
