-- Conversations, their messages and the job queue (T36, ADR 0002).
-- assignee_user_id and author_user_id hold core.users ids, but have no foreign key yet:
-- core.users is created by V3 (T30), and a later migration adds the foreign keys.

CREATE TABLE core.conversations (
    id                 UUID         PRIMARY KEY,
    business_id        BIGINT       NOT NULL REFERENCES core.businesses (id),
    channel            VARCHAR(16)  NOT NULL CHECK (channel IN ('WEB', 'WHATSAPP')),
    -- Who answers: the AI, nobody yet (waiting for staff), a staff member, or nobody (resolved).
    state              VARCHAR(16)  NOT NULL CHECK (state IN ('AI', 'WAITING', 'HUMAN', 'RESOLVED')),
    -- The staff member who took over. Always set in state HUMAN.
    assignee_user_id   BIGINT,
    language           VARCHAR(2)   CHECK (language IN ('en', 'es', 'pt')),
    -- Anonymous random id (WEB) or a hash of the phone number (WHATSAPP). Never the raw phone.
    customer_ref       VARCHAR(128) NOT NULL,
    -- Email or phone left by the customer, encrypted with AES-GCM (T41). Only with consent.
    customer_contact   TEXT,
    contact_consent_at TIMESTAMPTZ,
    -- SHA-256 (hex) of the conversation token given to the widget (T38).
    token_hash         VARCHAR(64)  UNIQUE,
    last_message_at    TIMESTAMPTZ  NOT NULL,
    unread_by_staff    INTEGER      NOT NULL DEFAULT 0 CHECK (unread_by_staff >= 0),
    created_at         TIMESTAMPTZ  NOT NULL,
    resolved_at        TIMESTAMPTZ,
    -- Grows by one on every state change (optimistic locking).
    version            BIGINT       NOT NULL DEFAULT 0,
    CHECK (state <> 'HUMAN' OR assignee_user_id IS NOT NULL),
    CHECK (customer_contact IS NULL OR contact_consent_at IS NOT NULL)
);

-- Inbox list: newest first, filtered by state or not. id breaks ties for cursor pagination.
CREATE INDEX idx_conversations_business_state_last_message
    ON core.conversations (business_id, state, last_message_at DESC, id DESC);
CREATE INDEX idx_conversations_business_last_message
    ON core.conversations (business_id, last_message_at DESC, id DESC);

CREATE TABLE core.messages (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id UUID         NOT NULL REFERENCES core.conversations (id),
    business_id     BIGINT       NOT NULL REFERENCES core.businesses (id),
    sender          VARCHAR(16)  NOT NULL CHECK (sender IN ('CUSTOMER', 'AI', 'HUMAN', 'SYSTEM')),
    -- Set only for HUMAN messages.
    author_user_id  BIGINT,
    body            TEXT         NOT NULL,
    -- true = staff only, never sent to the customer. Only staff and the system write internal messages.
    internal        BOOLEAN      NOT NULL DEFAULT FALSE,
    -- AI: intent, language, sources, orderCode. SYSTEM: event (TAKEN_OVER, RESOLVED...).
    metadata        JSONB        NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ  NOT NULL,
    CHECK (sender = 'HUMAN' OR author_user_id IS NULL),
    CHECK (NOT internal OR sender IN ('HUMAN', 'SYSTEM'))
);

-- History of one conversation, in order.
CREATE INDEX idx_messages_conversation_id ON core.messages (conversation_id, id);
CREATE INDEX idx_messages_business_id ON core.messages (business_id);

-- One platform-wide queue (no business_id column: the business is inside payload). Worker in T39.
CREATE TABLE core.jobs (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Each later task that adds a job type extends this list in its own migration.
    type        VARCHAR(32)  NOT NULL CHECK (type IN ('AI_REPLY')),
    payload     JSONB        NOT NULL DEFAULT '{}',
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    attempts    INTEGER      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    run_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_at   TIMESTAMPTZ,
    -- Error type only, never message text or personal data.
    last_error  VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The worker looks for due PENDING jobs.
CREATE INDEX idx_jobs_pending_run_at ON core.jobs (run_at) WHERE status = 'PENDING';
