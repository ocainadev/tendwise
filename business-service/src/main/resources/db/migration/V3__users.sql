-- Panel users (ADR 0003). No password or password hash is ever stored in a migration:
-- the platform admin and the demo users are created at startup from environment variables.
-- These tables belong to a user, not to a business, so they have no business_id
-- (users reach businesses through memberships).

CREATE TABLE core.users (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Stored lowercase, so "Ana@Example.com" and "ana@example.com" are the same user.
    email              VARCHAR(254) NOT NULL UNIQUE CHECK (email = lower(email)),
    -- Argon2id hash in PHC format ($argon2id$v=19$m=...).
    password_hash      VARCHAR(255) NOT NULL,
    name               VARCHAR(120) NOT NULL,
    -- ADMIN for the platform operator, null for everyone else.
    platform_role      VARCHAR(16)  CHECK (platform_role IN ('ADMIN')),
    -- Encrypted with AES-GCM (T68). Null until the user turns on 2FA.
    totp_secret        TEXT,
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    -- Lockout (T31): consecutive failed logins and the end of the current lock.
    failed_login_count INTEGER      NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at      TIMESTAMPTZ
);

-- One user can belong to several businesses, with one role in each.
CREATE TABLE core.memberships (
    user_id     BIGINT      NOT NULL REFERENCES core.users (id),
    business_id BIGINT      NOT NULL REFERENCES core.businesses (id),
    role        VARCHAR(16) NOT NULL CHECK (role IN ('OWNER', 'AGENT')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, business_id)
);

CREATE INDEX idx_memberships_business_id ON core.memberships (business_id);

-- Refresh tokens (used from T31). Only the SHA-256 hash of the token is stored.
-- Each login starts a family; reusing an old token revokes the whole family.
CREATE TABLE core.refresh_tokens (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES core.users (id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    family_id  UUID        NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user_id ON core.refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family_id ON core.refresh_tokens (family_id);
