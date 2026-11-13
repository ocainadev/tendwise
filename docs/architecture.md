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
- `handoffs.reason`: `COMPLAINT`, `HUMAN_REQUEST`, `NEGATIVE_SENTIMENT`, `LOW_CONFIDENCE` (validated by the API; no `CHECK` constraint yet).
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

#### RAG tables (`documents`, `chunks`)

- `business_id` is the business **slug** (`TEXT`, e.g. `joes-pizza`), not `core.businesses.id`. agent-service never reads the `core` schema (it only calls the business-service API), and every chat request carries the slug. The slug is the folder name in `data/businesses/<slug>/`.
- `documents`: one row per `.md` file. `source_path` is `<slug>/<file>.md`, unique per `(business_id, source_path)`; `title` is the file's first `# ` heading; `content_hash` is the sha256 of the file.
- `chunks`: `document_id` references `documents(id)` `ON DELETE CASCADE`; `business_id` is repeated so searches filter by it without a join; `metadata` holds the heading path, e.g. `{"section": "Joe's Pizza – Frequently asked questions > Delivery > Fees"}`.
- `embedding` is `vector(384)`: the multilingual model `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`, run locally with fastembed (ONNX, no API key). Changing the model means changing `EMBEDDING_MODEL`, `EMBEDDING_DIMENSIONS` and the column (new migration), then re-ingesting.
- Indexes: HNSW on `embedding vector_cosine_ops` (approximate nearest neighbours by cosine distance, `<=>`), and a B-tree on `chunks.business_id`.
- Ingestion (`app/rag/ingest.py`) runs in one transaction behind an advisory lock: unchanged hash → skipped; changed hash → the document's chunks are deleted and re-inserted; file removed → document deleted (chunks cascade).

#### Migrations

Plain SQL files in `agent-service/migrations/` (`001_documents_chunks.sql`, ...), applied in file-name order by `app/core/migrations.py` on app startup and at the start of every ingestion. Applied files are recorded in `agent.schema_migrations`.

## API contracts

### business-service

Prefix `/api/v1`. Every call requires the `X-Api-Key` header.

| Method | Route | Description |
|---|---|---|
| GET | `/businesses/{slug}` | Business info (hours, category, language) |
| GET | `/businesses/{slug}/catalog` | Catalog items |
| GET | `/orders/{code}/status?businessSlug=joes-pizza&phoneLast4=1234` | Status, fulfilment and ETA (no customer data). Returns 404 if the business or code does not exist, the order belongs to another business, or the phone does not match, without telling the cases apart. 400 if `phoneLast4` is not 4 digits |
| POST | `/handoffs` | Create handoff. Body: `businessSlug`, `conversationId` (UUID), `reason` (`COMPLAINT`, `HUMAN_REQUEST`, `NEGATIVE_SENTIMENT`, `LOW_CONFIDENCE`), `summary` (max 2000 chars). 201 with `Location: /api/v1/handoffs/{id}` and the handoff, status `OPEN`. 404 if the business does not exist, 400 if the body is invalid |
| GET | `/handoffs?businessSlug=joes-pizza&status=OPEN` | Handoffs of the business, oldest first. `status` is optional (absent = all). 404 if the business does not exist, 400 on an unknown status |
| PATCH | `/handoffs/{id}?businessSlug=joes-pizza` | Update status. Body: `status` (any of `OPEN`, `IN_PROGRESS`, `RESOLVED`; no state machine in the MVP). 200 with the handoff. 404 if the id does not exist or belongs to another business, without telling the cases apart |

Errors follow RFC 7807 (Problem Details). Docs via springdoc/Swagger at `/swagger-ui` (public; use "Authorize" to send the key).

- Missing or wrong `X-Api-Key` on `/api/**` → 401. The key comes from `BUSINESS_SERVICE_API_KEY`; the service does not start without it.
- `businessSlug` on the order status and handoff routes keeps tenant isolation: the agent can only read and change data of the business it is talking for.
- Handoff responses: `id`, `conversationId`, `reason`, `summary`, `status`, `createdAt`, `updatedAt`.

### agent-service

| Method | Route | Description |
|---|---|---|
| POST | `/api/v1/chat` | In: `conversation_id` (optional), `business_slug`, `message`. Out: `conversation_id`, `reply`, `intent`, `language`, `sources[]`, `handoff` (bool) |
| GET | `/health` | Service and database health |
| POST | `/api/v1/admin/ingest` | Re-index `data/businesses` (admin key required) |

- `POST /api/v1/admin/ingest` requires the `X-Admin-Key` header (compared in constant time with `ADMIN_API_KEY`): 401 if it is missing or wrong, 503 if `ADMIN_API_KEY` is not configured. 200 body: `{"added", "updated", "skipped", "deleted"}` (document counts).
