-- Knowledge gaps (T58): catalog questions the agent could not answer because no
-- chunk of the business reached RAG_MIN_SIMILARITY. The panel shows them,
-- grouped, so the owner knows what to add to the knowledge base.

-- business_id: the business slug, like agent.documents.
-- question:    the customer's message with phone numbers and emails masked.
-- best_similarity: the closest chunk of the business (below the threshold);
--              NULL when the business had no chunks at all.
-- embedding:   the masked question, embedded with the same model as the chunks.
--              Stored once here so grouping similar questions in the panel
--              needs no embedding model and no re-computation.
CREATE TABLE agent.knowledge_gaps (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id     TEXT        NOT NULL,
    question        TEXT        NOT NULL,
    best_similarity REAL,
    embedding       vector(384) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The panel reads one business's gaps in a date range.
CREATE INDEX knowledge_gaps_business_created_idx ON agent.knowledge_gaps (business_id, created_at);
