# Architecture

Source of truth for the data model and API contracts. Coding agents must not add endpoints, tables or fields that are not listed here; propose them in a PR instead.

## Overview

```
┌──────────────┐  HTTP   ┌──────────────────────────────┐  HTTP (tools)  ┌──────────────────────────┐
│ web          │───────▶│ agent-service                 │──────────────▶│ business-service          │
│ React + Vite │         │ Python · FastAPI · LangGraph  │  X-Api-Key     │ Java 21 · Spring Boot 3   │
└──────────────┘         │ RAG · guardrails · evals      │                │ businesses · catalog      │
                         └───────┬──────────────┬────────┘                │ orders · handoffs         │
                                 │              │                         └────────────┬─────────────┘
                          LLM API        ┌──────▼─────────────────────────────────────────▼──────┐
                   (OpenAI / Anthropic / │ PostgreSQL 16 + pgvector                              │
                    Gemini, swappable)   │ schema agent: documents, chunks, conversations, turns │
                                         │ schema core:  businesses, catalog_items, orders,      │
                                         │               handoffs                                │
                                         └───────────────────────────────────────────────────────┘
```

- The agent never reads the `core` schema directly; it only uses the business-service API through tools.
- Every table has `business_id`, and every query filters by it (multi-tenant).
- Why two services: [adr/0001-two-service-architecture.md](adr/0001-two-service-architecture.md).

## Data model

### Schema `core` (owned by business-service, Flyway migrations)

| Table | Main columns |
|---|---|
| `businesses` | id, slug (unique), name, category, default_language, timezone, opening_hours (jsonb), created_at |
| `catalog_items` | id, business_id, name, description, price, currency, available, kind (product/service) |
| `orders` | id, code (unique, e.g. JP-1042), business_id, customer_name, customer_phone, fulfilment (delivery/pickup/in_store), status, eta, created_at, updated_at |
| `handoffs` | id, business_id, conversation_id, reason, summary, status, created_at, updated_at |

- `orders.status`: `RECEIVED`, `IN_PROGRESS`, `READY`, `OUT_FOR_DELIVERY`, `COMPLETED`, `CANCELLED`.
- `orders.fulfilment`: `DELIVERY`, `PICKUP`, `IN_STORE`.
- `catalog_items.kind`: `PRODUCT`, `SERVICE`.
- `handoffs.status`: `OPEN`, `IN_PROGRESS`, `RESOLVED`.
- Enum values are stored as uppercase text with a `CHECK` constraint (mapped with `@Enumerated(EnumType.STRING)`).
- `handoffs.conversation_id` is a `UUID` created by agent-service (no foreign key across schemas).
- Migrations: `business-service/src/main/resources/db/migration` (`V1__schema.sql`, `V2__seed.sql`).

### Schema `agent` (owned by agent-service)

| Table | Main columns |
|---|---|
| `documents` | id, business_id, source_path, title, content_hash, updated_at |
| `chunks` | id, document_id, business_id, content, embedding vector(N), metadata (jsonb) |
| `conversations` | id, business_id, channel (web/whatsapp), language, status, created_at |
| `turns` | id, conversation_id, role, content, intent, tool_calls (jsonb), prompt_tokens, completion_tokens, latency_ms, cost_usd, model, created_at |

- `N` depends on the embedding model; set in config (`EMBEDDING_DIMENSIONS`) and in the migration.
- `content_hash` makes ingestion idempotent: unchanged documents are not reprocessed.

## API contracts

### business-service

Prefix `/api/v1`. Every call requires the `X-Api-Key` header.

| Method | Route | Description |
|---|---|---|
| GET | `/businesses/{slug}` | Business info (hours, category, language) |
| GET | `/businesses/{slug}/catalog` | Catalog items |
| GET | `/orders/{code}/status?businessSlug=joes-pizza&phoneLast4=1234` | Status, fulfilment and ETA (no customer data). Returns 404 if the business or code does not exist, the order belongs to another business, or the phone does not match, without telling the cases apart. 400 if `phoneLast4` is not 4 digits |
| POST | `/handoffs` | Create handoff: `businessSlug`, `conversationId`, `reason`, `summary` |
| GET | `/handoffs?status=OPEN` | List handoffs by status |
| PATCH | `/handoffs/{id}` | Update status |

Errors follow RFC 7807 (Problem Details). Docs via springdoc/Swagger at `/swagger-ui` (public; use "Authorize" to send the key).

- Missing or wrong `X-Api-Key` on `/api/**` → 401. The key comes from `BUSINESS_SERVICE_API_KEY`; the service does not start without it.
- `businessSlug` on the order status route keeps tenant isolation: the agent can only read orders of the business it is talking for.

### agent-service

| Method | Route | Description |
|---|---|---|
| POST | `/api/v1/chat` | In: `conversation_id` (optional), `business_slug`, `message`. Out: `conversation_id`, `reply`, `intent`, `language`, `sources[]`, `handoff` (bool) |
| GET | `/health` | Service and database health |
| POST | `/api/v1/admin/ingest` | Re-index `data/businesses` (admin key required) |
