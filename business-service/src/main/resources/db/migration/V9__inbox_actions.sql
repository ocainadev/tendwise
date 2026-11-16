-- Inbox actions (T44): who handled each handoff, and the audit log.
-- The user ids below are core.users ids without a foreign key, like conversations.assignee_user_id
-- (see V4).

-- assignee_user_id and assigned_at: the latest staff member who took the handoff (take over or
-- assign). first_response_at: the first public HUMAN message after the handoff (response-time
-- metric). resolved_at and resolved_by: when and by whom it ended (resolve or release to the AI).
ALTER TABLE core.handoffs
    ADD COLUMN assignee_user_id  BIGINT,
    ADD COLUMN assigned_at       TIMESTAMPTZ,
    ADD COLUMN first_response_at TIMESTAMPTZ,
    ADD COLUMN resolved_at       TIMESTAMPTZ,
    ADD COLUMN resolved_by       BIGINT;

-- Append-only record of staff actions. T44 writes the inbox actions; T64 adds support-mode
-- entries and the read API. business_id is null for platform-level actions. ip_hash is left null
-- by T44 (see docs/architecture.md, Security).
CREATE TABLE core.audit_log (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id BIGINT       NOT NULL,
    business_id   BIGINT       REFERENCES core.businesses (id),
    action        VARCHAR(64)  NOT NULL,
    target_type   VARCHAR(32)  NOT NULL,
    target_id     VARCHAR(64)  NOT NULL,
    metadata      JSONB        NOT NULL DEFAULT '{}',
    ip_hash       VARCHAR(64),
    support_mode  BOOLEAN      NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_business_id_created_at ON core.audit_log (business_id, created_at);
