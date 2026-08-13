CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS vector_chunks (
    chunk_id VARCHAR(64) PRIMARY KEY,
    file_name VARCHAR(512) NOT NULL,
    source_type VARCHAR(32) DEFAULT 'digital',
    language VARCHAR(32) DEFAULT 'mixed',
    chapter VARCHAR(256),
    section VARCHAR(256),
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1024),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_vector_chunks_embedding
    ON vector_chunks USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
