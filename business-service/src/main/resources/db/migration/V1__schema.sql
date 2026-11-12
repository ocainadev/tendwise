-- Schema core: owned by business-service. Flyway creates the schema itself
-- (spring.flyway.schemas=core). Every tenant-owned table has business_id.

CREATE TABLE core.businesses (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug             VARCHAR(64)  NOT NULL UNIQUE,
    name             VARCHAR(120) NOT NULL,
    category         VARCHAR(64)  NOT NULL,
    default_language VARCHAR(2)   NOT NULL CHECK (default_language IN ('en', 'es', 'pt')),
    timezone         VARCHAR(64)  NOT NULL,
    -- e.g. {"mon": [{"open": "11:00", "close": "23:00"}], "sun": []}
    opening_hours    JSONB        NOT NULL DEFAULT '{}',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE core.catalog_items (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id BIGINT         NOT NULL REFERENCES core.businesses (id),
    name        VARCHAR(120)   NOT NULL,
    description TEXT           NOT NULL DEFAULT '',
    price       NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    currency    VARCHAR(3)     NOT NULL, -- ISO 4217, e.g. USD, BRL
    available   BOOLEAN        NOT NULL DEFAULT TRUE,
    kind        VARCHAR(16)    NOT NULL CHECK (kind IN ('PRODUCT', 'SERVICE'))
);

CREATE INDEX idx_catalog_items_business_id ON core.catalog_items (business_id);

CREATE TABLE core.orders (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code           VARCHAR(32)  NOT NULL UNIQUE,
    business_id    BIGINT       NOT NULL REFERENCES core.businesses (id),
    customer_name  VARCHAR(120) NOT NULL,
    customer_phone VARCHAR(32)  NOT NULL,
    fulfilment     VARCHAR(16)  NOT NULL CHECK (fulfilment IN ('DELIVERY', 'PICKUP', 'IN_STORE')),
    status         VARCHAR(24)  NOT NULL CHECK (status IN (
                       'RECEIVED', 'IN_PROGRESS', 'READY', 'OUT_FOR_DELIVERY', 'COMPLETED', 'CANCELLED')),
    eta            TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_business_id ON core.orders (business_id);

CREATE TABLE core.handoffs (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_id     BIGINT      NOT NULL REFERENCES core.businesses (id),
    -- Conversation ids are created by agent-service; no foreign key across schemas.
    conversation_id UUID        NOT NULL,
    reason          VARCHAR(32) NOT NULL,
    summary         TEXT        NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_handoffs_business_id_status ON core.handoffs (business_id, status);
