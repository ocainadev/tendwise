-- Turn logging (T12): one row per conversation, one row per agent turn.
-- business_id is the business slug, like in agent.documents and agent.chunks.
-- The id is the conversation_id the chat API (T13) receives or creates.

CREATE TABLE agent.conversations (
    id          UUID        PRIMARY KEY,
    business_id TEXT        NOT NULL,
    channel     TEXT        NOT NULL DEFAULT 'web' CHECK (channel IN ('web', 'whatsapp')),
    language    TEXT,                             -- en | es | pt, from the first turn
    status      TEXT        NOT NULL DEFAULT 'active',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent.turns (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id   UUID          NOT NULL REFERENCES agent.conversations (id) ON DELETE CASCADE,
    role              TEXT          NOT NULL,     -- always 'assistant': one row per answered turn
    content           TEXT          NOT NULL,     -- the final reply, phone numbers masked
    intent            TEXT,                       -- NULL when guard_input blocked the message
    tool_calls        JSONB         NOT NULL DEFAULT '[]'::jsonb,
    prompt_tokens     INT           NOT NULL DEFAULT 0,
    completion_tokens INT           NOT NULL DEFAULT 0,
    latency_ms        INT           NOT NULL,
    cost_usd          NUMERIC(10,6) NOT NULL DEFAULT 0,
    model             TEXT,                       -- NULL when the turn made no LLM call
    -- Not in spec section 7: kept as columns so the report needs no JSON parsing.
    blocked           BOOLEAN       NOT NULL DEFAULT false,
    handoff           BOOLEAN       NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX turns_conversation_id_idx ON agent.turns (conversation_id);
-- The report and the purge filter by date.
CREATE INDEX turns_created_at_idx ON agent.turns (created_at);
CREATE INDEX conversations_business_id_idx ON agent.conversations (business_id);
