package com.rag.eval.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class VectorChunkRepo {

    private final JdbcTemplate jdbc;

    public VectorChunkRepo(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String chunkId, String fileName, String sourceType, String language,
                       String chapter, String section, int chunkIndex, String content, String embeddingStr) {
        jdbc.update(
            "INSERT INTO vector_chunks (chunk_id, file_name, source_type, language, chapter, section, chunk_index, content, embedding) " +
            "VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?::vector)",
            chunkId, fileName, sourceType, language, chapter, section, chunkIndex, content, embeddingStr
        );
    }

    public void deleteByFileName(String fileName) {
        jdbc.update("DELETE FROM vector_chunks WHERE file_name = ?", fileName);
    }

    public List<VectorSearchRow> similaritySearch(String queryEmbedding, double threshold, int topK) {
        String sql = """
            SELECT chunk_id, file_name, chapter, section, content, source_type,
                   1 - (embedding <=> ?::vector) AS similarity
            FROM vector_chunks
            WHERE 1 - (embedding <=> ?::vector) >= ?
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """;
        return jdbc.query(sql,
            (rs, rowNum) -> new VectorSearchRow(
                rs.getString("chunk_id"),
                rs.getString("file_name"),
                rs.getString("chapter"),
                rs.getString("section"),
                rs.getString("content"),
                rs.getString("source_type"),
                rs.getDouble("similarity")
            ),
            queryEmbedding, queryEmbedding, threshold, queryEmbedding, topK
        );
    }

    public record VectorSearchRow(String chunkId, String fileName, String chapter, String section,
                                   String content, String sourceType, double similarity) {}
}
