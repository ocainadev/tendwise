-- Knowledge base in the database (T50). Business owners edit their documents in
-- the panel; the data/businesses files only seed the demo businesses.

-- content:    the markdown itself (the source of truth from now on).
-- source:     where the document came from. FILE = a seed file in data/businesses,
--             EDITOR = written or edited in the panel (a restart never overwrites it).
--             UPLOAD (T19) and CATALOG (T52) are reserved for later tasks.
-- status:     PENDING while its chunks are being (re)built, then INDEXED or FAILED.
-- updated_by: user id from the panel token; NULL for seed files.
ALTER TABLE agent.documents
    ADD COLUMN content    TEXT        NOT NULL DEFAULT '',
    ADD COLUMN source     VARCHAR(16) NOT NULL DEFAULT 'FILE'
        CONSTRAINT documents_source_check CHECK (source IN ('FILE', 'EDITOR', 'UPLOAD', 'CATALOG')),
    ADD COLUMN status     VARCHAR(16) NOT NULL DEFAULT 'INDEXED'
        CONSTRAINT documents_status_check CHECK (status IN ('PENDING', 'INDEXED', 'FAILED')),
    ADD COLUMN updated_by BIGINT;

-- Rows ingested before this migration have no content yet. A hash that never
-- matches a file makes the next startup ingestion re-read every file, which
-- fills `content` (and rebuilds the same chunks).
UPDATE agent.documents SET content_hash = 'before-003';

-- agent-service's own job queue: same columns and statuses as core.jobs
-- (agent-service never touches the core schema). The business is inside
-- `payload`, like in core.jobs: {"business_id": "...", "document_id": 1, "content_hash": "..."}.
CREATE TABLE agent.jobs (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Each later task that adds a job type extends this list in its own migration.
    type        VARCHAR(32)  NOT NULL CONSTRAINT jobs_type_check CHECK (type IN ('INDEX_DOCUMENT')),
    payload     JSONB        NOT NULL DEFAULT '{}',
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    attempts    INTEGER      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    run_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_at   TIMESTAMPTZ,
    -- Error type only, never message text or document content.
    last_error  VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The worker looks for due PENDING jobs.
CREATE INDEX jobs_pending_run_at_idx ON agent.jobs (run_at) WHERE status = 'PENDING';
