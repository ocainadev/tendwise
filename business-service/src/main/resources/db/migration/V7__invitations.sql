-- Team invitations (T34). An OWNER invites an email with a role; the invitee accepts through
-- POST /auth/invitations/accept with the token from the email. Only the SHA-256 hash of the token
-- is stored. Valid 7 days, single use (accepted_at). Revoking a pending invitation deletes its row.

CREATE TABLE core.invitations (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id BIGINT       NOT NULL REFERENCES core.businesses (id),
    -- Stored lowercase, like core.users.email.
    email       VARCHAR(254) NOT NULL CHECK (email = lower(email)),
    role        VARCHAR(16)  NOT NULL CHECK (role IN ('OWNER', 'AGENT')),
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    invited_by  BIGINT       NOT NULL REFERENCES core.users (id),
    expires_at  TIMESTAMPTZ  NOT NULL,
    -- Null while the invitation is pending.
    accepted_at TIMESTAMPTZ
);

CREATE INDEX idx_invitations_business_id ON core.invitations (business_id);
