-- RAG storage (T07): one row per ingested .md file, one row per chunk.
-- business_id is the business slug (e.g. 'joes-pizza'), not the core id:
-- agent-service never reads the core schema, and every chat request carries the slug.

-- Compose already enables pgvector (infra/db/init); this keeps a fresh database working too.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE agent.documents (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id  TEXT        NOT NULL,
    source_path  TEXT        NOT NULL,  -- e.g. 'joes-pizza/faq.md'
    title        TEXT        NOT NULL,
    content_hash TEXT        NOT NULL,  -- sha256 of the file: unchanged files are skipped
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (business_id, source_path)
);

CREATE TABLE agent.chunks (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id BIGINT      NOT NULL REFERENCES agent.documents (id) ON DELETE CASCADE,
    business_id TEXT        NOT NULL,
    content     TEXT        NOT NULL,
    -- 384 = EMBEDDING_DIMENSIONS of paraphrase-multilingual-MiniLM-L12-v2.
    embedding   vector(384) NOT NULL,
    metadata    JSONB       NOT NULL DEFAULT '{}'::jsonb  -- e.g. {"section": "FAQ > Delivery"}
);

-- HNSW: approximate nearest-neighbour index for cosine distance (the <=> operator).
CREATE INDEX chunks_embedding_hnsw_idx ON agent.chunks USING hnsw (embedding vector_cosine_ops);
-- Every search filters by business first (tenant isolation).
CREATE INDEX chunks_business_id_idx ON agent.chunks (business_id);
CREATE INDEX chunks_document_id_idx ON agent.chunks (document_id);
