# Architecture

Source of truth for the data model and API contracts. Coding agents must not add endpoints, tables or fields that are not listed here; propose them in a PR instead.

**Built vs planned.** Everything without a marker describes v0.1.0 and exists in the code. Items marked **planned: T36** are approved (control panels, [panels-plan.md](panels-plan.md), decided on 2026-11-13) but not built yet: the named task is allowed to add them, and nobody else should. Items marked **deferred** are mentioned in the plan but have no contract yet; the task named next to them proposes one in its PR. Tasks are listed in [project-spec.md, section 11](project-spec.md#after-the-mvp-control-panels).

## Overview

### v0.1.0 (built)

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
- Why conversations move to business-service: [adr/0002-conversations-in-business-service.md](adr/0002-conversations-in-business-service.md). Panel login: [adr/0003-panel-authentication.md](adr/0003-panel-authentication.md). Asynchronous replies and live updates: [adr/0004-realtime-and-jobs.md](adr/0004-realtime-and-jobs.md).
- **LLM providers:** the agent depends only on the `LLMClient` port (`app/llm/client.py`); `LLM_PROVIDER` picks the adapter in `app/llm/factory.py`. Supported: `anthropic` (Messages API, key `ANTHROPIC_API_KEY`, default model `claude-haiku-5-5`) and `openai` (Responses API with `reasoning.effort=none` and `store=false`, key `OPENAI_API_KEY`, default model `gpt-6-luna`). `LLM_MODEL` overrides the default; only the selected provider's key is needed. Gemini is planned. Provider errors are caught through `app/llm/errors.py`, so callers never import an SDK.

### With the control panels (planned: T30–T73)

```
 customer: website chat widget                   staff and operator: panels in web
 (iframe; the /demo page embeds it)              (/app/:slug/*, /admin/*)
        │ /public/v1/** + SSE                            │ /auth/**, /api/v1/panel/**, /api/v1/admin/** + SSE
        │ conversation token (header)                    │ JWT and CSRF cookies
        ▼                                                ▼
┌───────────────────────────────────────────────────────────────────────────────────────────────┐
│ nginx (web): serves the panels, the demo and the widget, and sends each path to one service   │
└───────────────┬───────────────────────────────────────────────────────────────┬───────────────┘
                │ /public, /auth, /api/v1/panel, /api/v1/admin                  │ /api/v1/panel/{slug}/documents,
                ▼                                                               ▼ /knowledge-gaps, /playground
┌──────────────────────────────────────┐   POST /internal/v1/replies   ┌──────────────────────────────┐
│ business-service                     │──────────────────────────────▶│ agent-service                │
│ Java 21 · Spring Boot 3              │                               │ Python · FastAPI · LangGraph │
│ login (JWT, JWKS) · businesses       │   tools: /api/v1/businesses,  │ RAG · guardrails · evals     │
│ catalog · orders · handoffs          │◀──────────────────────────────│ knowledge base               │
│ conversations · messages · jobs      │   /orders, /handoffs          │ verifies the JWT (JWKS)      │
│ message router · job worker · SSE    │   (X-Api-Key)                 │                              │
└──────────────────┬───────────────────┘                               └───────┬───────────────┬──────┘
                   │                                                           │               │
                   │       ┌───────────────────────────────────────────────────▼──────┐    LLM API
                   └──────▶│ PostgreSQL 16 + pgvector                                 │
                           │ schema core: owned by business-service                   │
                           │ schema agent: owned by agent-service                     │
                           │ LISTEN/NOTIFY: events reach every business-service       │
                           │ instance                                                 │
                           └──────────────────────────────────────────────────────────┘
```

- **The router:** business-service owns conversations and messages and decides who answers each customer message: the AI (an `AI_REPLY` job that calls agent-service), nobody yet (`WAITING`, staff are notified) or the staff member who took the conversation (`HUMAN`). See [Conversations and message routing](#conversations-and-message-routing).
- **The panels:** one React app in `web` with three areas: `/app/:slug/*` business panel, `/admin/*` platform admin, `/demo` (the page that embeds the chat widget). The widget is a separate small bundle (`widget/`, plain TypeScript) loaded in an iframe (planned: T56).
- agent-service still never reads the `core` schema, and business-service never reads the `agent` schema: usage numbers come through `GET /internal/v1/usage` (planned: T59).
- Until T42, the browser still calls agent-service `POST /api/v1/chat` through nginx, as in v0.1.0.
- **Panel shell (T33, done):** React Router in `web`: `/` redirects to `/demo` (the v0.1.0 chat), `/login`, `/app` (business chooser), `/app/:slug/*` (sidebar with the business switcher and the menu items the role may open) and `/admin/*` (platform admins only). `src/api/http.ts` adds the `X-CSRF-Token` header to state-changing requests and, on a `401`, sends one shared `POST /auth/refresh` and retries the request once. The logged-in user is the `/auth/me` entry in the TanStack Query cache. Texts come from react-i18next (`src/i18n/pt-BR.ts`; `en` and `es` are added as files of the same shape).

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

#### Planned `core` tables (control panels)

Each table is created by the task in the "Planned" column (its Flyway migration, see [Migration numbers](#migration-numbers)).

| Table | Main columns | Planned | Notes |
|---|---|---|---|
| `users` | id, email (unique, stored lowercase), password_hash, name, platform_role (`ADMIN` or null), totp_secret (encrypted, null), status (`ACTIVE`, `DISABLED`), failed_login_count, locked_until, created_at, last_login_at | T30 | Argon2id hashes. `totp_secret` stays null until T68. `failed_login_count` and `locked_until` hold the lockout (T31) |
| `memberships` | user_id, business_id, role (`OWNER`, `AGENT`), created_at | T30 | PK (user_id, business_id). One user can belong to several businesses |
| `refresh_tokens` | id, user_id, token_hash, family_id, expires_at, revoked_at, created_at | T30 | Used from T31: rotated on every use; a reused token revokes its whole family |
| `invitations` | id, business_id, email, role, token_hash, invited_by, expires_at, accepted_at | T34 | Token sent by email, stored hashed, valid 7 days, single use |
| `password_resets` | id, user_id, token_hash, expires_at, used_at, created_at | T69 | Token sent by email, stored hashed, valid 1 hour, single use |
| `plans` | id, code (unique), name, price_cents, currency, conversation_limit, seat_limit, features (jsonb) | T62 | Editable by the operator. `features`: `{"whatsapp": bool, "model": "<LLM model id>"}` |
| `conversations` | id (UUID), business_id, channel (`WEB`, `WHATSAPP`), state (`AI`, `WAITING`, `HUMAN`, `RESOLVED`), assignee_user_id, language, customer_ref, customer_contact (encrypted, null), contact_consent_at, token_hash, last_message_at, unread_by_staff, created_at, resolved_at, version | T36 | See [Conversations and message routing](#conversations-and-message-routing) |
| `messages` | id, conversation_id, business_id, sender (`CUSTOMER`, `AI`, `HUMAN`, `SYSTEM`), author_user_id, body, internal (bool), metadata (jsonb), created_at | T36 | `internal = true` is never sent to the customer |
| `jobs` | id, type, payload (jsonb), status (`PENDING`, `RUNNING`, `DONE`, `FAILED`), attempts, run_at, locked_at, last_error, created_at | T36 | Worker T39. See [Jobs](#jobs) |
| `canned_replies` | id, business_id, shortcut, title, body, created_by | T48 | `shortcut` unique per business (typed after `/`) |
| `api_keys` | id, business_id, name, prefix, key_hash, scopes, last_used_at, revoked_at | T57 | For the orders integration. The full key is shown once on creation; only the hash is stored |
| `usage_daily` | business_id, day, conversations, customer_messages, ai_messages, human_messages, handoffs, resolved_by_ai, llm_cost_usd | T59 | PK (business_id, day). Filled by the nightly `USAGE_ROLLUP` job; re-running a day replaces its row |
| `audit_log` | id, actor_user_id, business_id, action, target_type, target_id, metadata (jsonb), ip_hash, support_mode (bool), created_at | T44 | Append-only. T44 creates it and writes the inbox actions; T64 adds support-mode entries and the read API. `business_id` is null for platform-level actions |
| `satisfaction` | conversation_id, business_id, rating (`UP`, `DOWN`), comment, created_at | T61 | PK conversation_id: one rating per conversation |

New columns on existing tables:

| Table | New columns | Planned | Notes |
|---|---|---|---|
| `businesses` | publishable_key (unique), widget_settings (jsonb) | T38 | `widget_settings`: `allowedDomains` (list), `color`, `position`. The demo businesses are seeded with the local origins |
| `businesses` | handoff_settings (jsonb) | T41 | `autoAckMinutes` (default 3), `waitingAlertMinutes` (email to owners, T48) |
| `businesses` | assistant_settings (jsonb) | T53 | `name`, `tone`, `greeting`, `extraInstructions` (max 1,000 characters), `languages` (enabled, subset of `en`, `es`, `pt`), `outOfHoursMessage` |
| `businesses` | status (`TRIAL`, `ACTIVE`, `SUSPENDED`, `CANCELLED`), plan_id, trial_ends_at | T62 | Existing businesses become `ACTIVE` |
| `handoffs` | assignee_user_id, assigned_at, first_response_at, resolved_at, resolved_by | T44 | `first_response_at` = first `HUMAN` message after the handoff; feeds the response-time metric |

- **`business_id` rule for the new tables:** every new table has `business_id` and every query filters by it, except `users`, `refresh_tokens` and `password_resets` (they belong to a user, who reaches businesses through `memberships`), `plans` (platform-wide) and `jobs` (one platform-wide queue; the business is inside `payload`).
- Settings columns are jsonb in the database and validated DTOs (records) in Java; the API never accepts a key that is not listed above.
- Encrypted columns (`users.totp_secret`, `conversations.customer_contact`) use AES-GCM with a key from the environment; the key is never in the repository.
- After T36, `handoffs.conversation_id` is a `core.conversations.id` created by business-service. There is still no foreign key, because v0.1.0 handoffs point to conversations that exist only in the `agent` schema; T41 looks the conversation up in the business. Until T42, an id that is not there (a demo-chat conversation that lives only in agent-service) still gets a handoff, with no state change (see `POST /handoffs` below).
- `handoffs.status` follows the conversation from T41 (same transaction, in `ConversationService`): `WAITING` → `OPEN`, `HUMAN` → `IN_PROGRESS`, `RESOLVED` or back to `AI` → `RESOLVED`. `PATCH /handoffs/{id}` can still set any status until T44 removes it.

#### Migration numbers

Parallel Java PRs must not pick the same Flyway version:

- **T30** adds `V3__users.sql` (`users`, `memberships`, `refresh_tokens`). The platform admin is created at startup from environment variables, together with a demo owner and agent per demo business; no migration contains a password or a password hash.
- **T36** adds `V4__conversations.sql` (`conversations`, `messages`, `jobs`).
- **T38** adds `V5__widget.sql` (`businesses.publishable_key`, `businesses.widget_settings`). The demo businesses get `pk_demo_joes_pizza` and `pk_demo_denim_corner` and allow `http://localhost:5173` and `http://127.0.0.1:5173`.
- **T41** adds `V6__handoff_settings.sql` (`businesses.handoff_settings`, job type `AUTO_ACK`).
- **T34** adds `V7__invitations.sql` (`invitations`).
- **T53** adds `V8__assistant_settings.sql` (`businesses.assistant_settings`, default `{}`).
- Every later Java task takes **the next free number at the time it starts** (`V5__…`, `V6__…`, …). If another PR with the same number lands first, rebase and renumber before merging. Never edit a migration that is already on main.

### Schema `agent` (owned by agent-service)

| Table | Main columns |
|---|---|
| `documents` | id, business_id, source_path, title, content_hash, updated_at, content, source, status, updated_by (T50) |
| `chunks` | id, document_id, business_id, content, embedding vector(N), metadata (jsonb) |
| `jobs` | id, type, payload (jsonb), status, attempts, run_at, locked_at, last_error, created_at (T50; see [Knowledge base](#knowledge-base-t50)) |
| `conversations` | id, business_id, channel (web/whatsapp), language, status, created_at |
| `turns` | id, conversation_id, role, content, intent, tool_calls (jsonb), prompt_tokens, completion_tokens, latency_ms, cost_usd, model, created_at |

- `N` depends on the embedding model; set in config (`EMBEDDING_DIMENSIONS`) and in the migration.
- `content_hash` makes ingestion idempotent: unchanged documents are not reprocessed.

#### RAG tables (`documents`, `chunks`)

- `business_id` is the business **slug** (`TEXT`, e.g. `joes-pizza`), not `core.businesses.id`. agent-service never reads the `core` schema (it only calls the business-service API), and every chat request carries the slug. The slug is the folder name in `data/businesses/<slug>/`.
- `documents`: one row per document. `source_path` is its unique name within the business, `<slug>/<name>.md` (for a seed file, the file name); `title` is the file's first `# ` heading for a seed file, or the title typed in the panel; `content` is the markdown and `content_hash` its sha256. Since T50 the database is the source of truth; see [Knowledge base](#knowledge-base-t50).
- `chunks`: `document_id` references `documents(id)` `ON DELETE CASCADE`; `business_id` is repeated so searches filter by it without a join; `metadata` holds the heading path, e.g. `{"section": "Joe's Pizza – Frequently asked questions > Delivery > Fees"}`.
- `embedding` is `vector(384)`: the multilingual model `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`, run locally with fastembed (ONNX, no API key). Changing the model means changing `EMBEDDING_MODEL`, `EMBEDDING_DIMENSIONS` and the column (new migration), then re-ingesting.
- Indexes: HNSW on `embedding vector_cosine_ops` (approximate nearest neighbours by cosine distance, `<=>`), and a B-tree on `chunks.business_id`.
- Ingestion (`app/rag/ingest.py`) seeds the demo businesses from `data/businesses` (`source = FILE`). It runs in one transaction behind an advisory lock: unchanged hash → skipped; changed hash → the document's content and chunks are replaced; file removed → document deleted (chunks cascade). A document edited or written in the panel (`source = EDITOR`) is never touched (not updated, not deleted).
- Ingestion runs on every agent-service startup (after migrations, before the agent is built), so a fresh `docker compose up` has an index. A failure is only logged (error type only) and the service still starts; `scripts/ingest.py` and `POST /api/v1/admin/ingest` re-index by hand.

#### Retrieval

- `app/rag/retriever.py`: the agent depends on the `Retriever` interface (`retrieve(business_slug, query, top_k=5) -> list[RetrievedChunk]`); `PgVectorRetriever` is the adapter. Each `RetrievedChunk` has `content`, `source_path`, `title`, `section` and `similarity`.
- **Business filter:** one SQL query, always `WHERE c.business_id = <slug>`, joined with `documents` on both `id` and `business_id`. A question for one business can never return another business's chunks.
- **Top-k:** `ORDER BY embedding <=> query LIMIT top_k` (`RAG_TOP_K`, default 5). `similarity = 1 - cosine distance`; results come back best first. `SET LOCAL hnsw.iterative_scan = relaxed_order` keeps a filtered HNSW scan from returning fewer than `top_k` rows when the index is used.
- **Cross-language queries (T27):** the documents are written in the business's default language, and a short question in another language can land under the threshold (e.g. "¿Cuál es la política de cambios?" 0.21 against Portuguese documents). For catalog questions the classifier also returns `search_query`, the question rewritten as a short standalone query in the business's default language. The `retrieve` node searches with the customer's message and with `search_query`, keeps the best score of each chunk found twice, and keeps the `top_k` best. Searching with the original message too means the rewrite can only add chunks, never lose them.
- **Threshold:** chunks with similarity below `RAG_MIN_SIMILARITY` (default **0.30**) are dropped after the query (a `WHERE` on the distance would stop PostgreSQL from using the index). An off-topic question returns `[]`, and the agent answers that it does not know instead of guessing.
- **Calibration (T08), with the real model:** 30 on-topic questions (12 per business in its language, 3 in another language) all have their best chunk at ≥ 0.347 (median ≈ 0.53). Of 16 off-topic questions, 12 have their best chunk below 0.30 (e.g. "Who won the world cup?" 0.153 / 0.073). The few above it (weather 0.37–0.41, "ignore your instructions" 0.32–0.33) only reach harmless chunks such as opening hours; the intent classifier and input guard stop those first anyway. Between 0.28 and 0.30, 13 of 14 chunks were noise. Re-check the threshold whenever the model or the content changes.

#### Migrations

Plain SQL files in `agent-service/migrations/` (`001_documents_chunks.sql`, ...), applied in file-name order by `app/core/migrations.py` on app startup and at the start of every ingestion. Applied files are recorded in `agent.schema_migrations`.

#### Knowledge base (T50)

Owners edit the documents in the panel ([routes](#panel-api-planned)); the `data/businesses` files are only the seed of the demo businesses.

- **Migration** `003_knowledge_base.sql`: `documents.content` (text), `source` (`FILE`, `EDITOR`; `UPLOAD` and `CATALOG` reserved for T19 and T52), `status` (`PENDING`, `INDEXED`, `FAILED`), `updated_by` (panel user id, null for files), and `agent.jobs`. It resets the hash of the rows ingested before it, so the next startup ingestion re-reads every file and fills `content`.
- **Saving is instant:** create and update store the text with status `PENDING` and enqueue an `INDEX_DOCUMENT` job (payload `business_id`, `document_id`, `content_hash`) in the same transaction. A title-only change keeps the chunks and needs no job. Until the job finishes, the retriever keeps the document's previous chunks; a new document has none yet.
- **The job** (`app/rag/indexing.py`) reads the document only if it still has the job's `content_hash`, chunks and embeds it outside any transaction, then, holding the row lock and checking the hash again, replaces its chunks and sets `INDEXED`. A job for an older version or a deleted document does nothing, so a slow old job never overwrites newer chunks. After the last failed attempt the document is `FAILED` (the job's `last_error` keeps the error type only); a new save or a reindex tries again.
- **Worker** (`app/core/jobs.py`): the same rules as [Jobs](#jobs) (`FOR UPDATE SKIP LOCKED`, 3 attempts, 5 s backoff doubling, stale `RUNNING` after 5 minutes back to `PENDING`), run by one asyncio task started at startup that takes one job at a time in a thread (`asyncio.to_thread`): indexing is CPU work, so running more at once would not make it faster. It loads its own embedding model in the background, so startup does not wait for it.
- **Seed vs. panel:** editing a `FILE` document turns it into `EDITOR`, so a restart never overwrites the owner's text, even if the file changed. The seed locks the row (`FOR UPDATE`) before deciding, so a panel save during startup ingestion is never lost. Deleting a `FILE` document in the panel removes it until the next restart, which seeds it again from its file (the demo businesses reset to their files); to change a seed document for good, edit it.
- **Names:** a new document's `source_path` comes from its title (`Horário & Entrega` → `<slug>/horario-entrega.md`, then `-2`, `-3` if taken) and never changes afterwards.
- **Limits** (`app/rag/documents.py`): title 1–200 characters, text 1–50,000 characters per document (both `422` from validation), at most 50 documents and 500,000 characters in all per business (`422` with a readable detail). At ~800 characters per chunk that is at most ~600 chunks per business, a few seconds to re-embed on a laptop. Saves of one business take an advisory lock, so two parallel saves cannot both pass the limits. Seed files do not check the limits.

#### Planned `agent` changes (control panels)

| Table | Change | Planned |
|---|---|---|
| `documents` | New columns `content` (text), `source` (`FILE`, `EDITOR`, `UPLOAD`, `CATALOG`), `status` (`PENDING`, `INDEXED`, `FAILED`), `updated_by` (user id from the panel JWT, null for files). Knowledge moves from files to the database; the `data/businesses` files stay as the seed of the demo businesses (`source = FILE`). `source_path` stays the document's unique name within the business | Done in T50 (`UPLOAD`: T19, `CATALOG`: T52) |
| `jobs` | New, same columns, statuses and `SKIP LOCKED` worker as `core.jobs` (agent-service never touches the `core` schema, so it needs its own queue). Types: `INDEX_DOCUMENT` (T50), `REGENERATE_CATALOG` (T52, which extends `jobs_type_check`) | Done in T50 |
| `knowledge_gaps` | New: id, business_id, question (phones and emails masked), best_similarity, created_at. One row per catalog question whose best chunk is below `RAG_MIN_SIMILARITY`. Blocked messages are not recorded. Similar questions are grouped when the panel reads them; a grouping column, if needed, is proposed in T58 | T58 |
| `conversations` | `id` is the same UUID as `core.conversations.id` (sent by business-service from T37). `channel` also takes `playground` for knowledge-page test chats, which the usage numbers leave out | T37, T51 |
| `turns` | Unchanged: AI telemetry | – |

#### Planned agent migrations

T50 added `003_knowledge_base.sql` (`documents` columns and `agent.jobs`). Every later agent-service task that needs a migration takes the next free number when it starts (`004_…`, `005_…`), and renumbers after a rebase if another PR took it first.

## Conversations and message routing

Planned: T36 (table, state machine), T38 (public API), T39 (AI reply job), T41 (handoff link, auto-ack), T44 (staff actions). Why: [ADR 0002](adr/0002-conversations-in-business-service.md).

### State machine

One field, `core.conversations.state`, decides who answers.

```
            handoff created (AI or customer asked), or AI reply failed 3 times
   ┌────┐ ─────────────────────────────────────▶ ┌─────────┐   staff takes over   ┌───────┐
   │ AI │                                         │ WAITING │ ───────────────────▶ │ HUMAN │
   └────┘ ◀───────────────────────────────────── └─────────┘                      └───────┘
     ▲  ▲        staff hands back to AI               │                             │  │
     │  └─────────────────────────────────────────────┼─────────────────────────────┘  │
     │                                                ▼ resolve                         ▼ resolve
     │   customer writes again                  ┌──────────┐ ◀──────────────────────────┘
     └──────────────────────────────────────────│ RESOLVED │
                                                └──────────┘
```

| From | To | Trigger | Planned |
|---|---|---|---|
| `AI` | `WAITING` | A handoff is created (`POST /api/v1/handoffs`, called by the agent's `create_handoff` node) | T41 |
| `AI` | `WAITING` | The `AI_REPLY` job failed its last attempt | T39 |
| `WAITING` | `HUMAN` | Staff takes over, or assigns it to a colleague | T44 |
| `HUMAN` | `HUMAN` | Staff assigns it to a colleague (only the assignee changes) | T44 |
| `WAITING`, `HUMAN` | `AI` | Staff hands it back to the AI (release) | T44 |
| `AI`, `WAITING`, `HUMAN` | `RESOLVED` | Staff resolves it (from `AI` too, e.g. a conversation the AI finished) | T44 |
| `RESOLVED` | `AI` | The customer writes again (the conversation reopens) | T38 |

- **AI:** each customer message enqueues an `AI_REPLY` job.
- **WAITING:** the AI does not answer. Messages are stored, `unread_by_staff` grows and staff are notified. If nobody takes over within `handoff_settings.autoAckMinutes` (default 3), or the business is closed (`opening_hours`), the customer gets **one** automatic `SYSTEM` message (for example "Já avisei a equipe; respondemos a partir das 9h. Quer deixar um e-mail ou WhatsApp?"), sent by an `AUTO_ACK` job (T41).
- **Auto-ack (T41):** creating the handoff queues one `AUTO_ACK` job with `run_at` = now when the business is closed, otherwise now + `autoAckMinutes`. When it runs, it sends nothing if the conversation is no longer `WAITING`, the handoff is no longer `OPEN`, or this handoff's auto-ack was already sent, so a retried job still sends one message. The message is `SYSTEM`, `internal = false`, with `metadata` `event: AUTO_ACK`, `handoffId`, `businessOpen` and, when closed, `opensAt` (ISO date-time in the business's time zone); the widget writes the customer-facing text from these fields in the conversation's language. Opening hours: an empty `opening_hours` (`{}`) means unknown and counts as always open; a day missing from a non-empty object is closed; a `close` at or before `open` ends on the next day.
- **HUMAN:** only the assignee answers; the AI is silent.
- **Back to AI:** the next `AI_REPLY` sends the messages exchanged with staff since the last AI turn (`staff_messages`), so the AI knows what a person said.
- **Every transition** is one conditional `UPDATE conversations SET state = …, version = version + 1 WHERE id = … AND business_id = … AND state = <expected>` in a transaction that also writes a `SYSTEM` message and sends the event. If no row is updated, someone else changed the state first: the API answers `409 Conflict` with the current state and assignee ("Maria already took this conversation").
- `SYSTEM` messages describe what happened (`metadata.event`: `HANDOFF_CREATED`, `TAKEN_OVER`, `ASSIGNED`, `RELEASED`, `RESOLVED`, `REOPENED`, `AUTO_ACK`, `AI_FAILED`). The ones the customer should see ("Maria joined", "back to the assistant", the auto-ack, `AI_FAILED` "a team member will reply here") have `internal = false`; staff-only ones (the handoff reason, assignments) have `internal = true`.

### Conversation columns

- `customer_ref`: an anonymous random id created with a `WEB` conversation, or a hash of the phone number for `WHATSAPP` (T18). Never the raw phone.
- `customer_contact` and `contact_consent_at`: the email or phone the customer left after a handoff (`POST /public/v1/conversations/{id}/contact`, T41), stored only with consent and encrypted.
- `token_hash`: SHA-256 of the conversation token (random 256 bits, returned once when the conversation starts).
- `unread_by_staff`: number of customer messages since staff last opened the conversation; `POST …/read` sets it to 0.
- `version`: grows by one on every state change (JPA `@Version`), a second safety net next to the conditional update.
- `messages.metadata` for `AI` messages: `intent`, `language`, `sources` (`title`, `source_path`, `section`) and `orderCode` (the order code the agent saw, if any), copied from the `/internal/v1/replies` response. `author_user_id` is set only for `HUMAN` messages.

### Message flow in state `AI`

1. The widget sends `POST /public/v1/conversations/{id}/messages`. business-service stores the `CUSTOMER` message, inserts an `AI_REPLY` job in the same transaction and answers `202` with the stored message.
2. A worker takes the job (`SELECT … FOR UPDATE SKIP LOCKED`), sends `ai.typing`, calls agent-service `POST /internal/v1/replies` and stores the `AI` message.
3. If the agent created a handoff during that call, the state is already `WAITING` (the handoff endpoint changed it), and the reply is the fixed "a person will continue" text. If no handoff was created and the state is no longer `AI` when the reply comes back (staff took over meanwhile), the reply is dropped, so the AI never talks over a person.
4. Each stored message sends a `NOTIFY` after the commit. Every business-service instance pushes it to the SSE streams of that conversation (widget) and that business (panel).
5. The widget shows "typing…" between steps 1 and 4. A failed call retries with backoff (3 attempts in total); after the last one the worker stores a `SYSTEM` message (`AI_FAILED`) and moves the conversation to `WAITING`, so a person picks it up instead of the customer waiting forever.

In states `WAITING` and `HUMAN` step 1 stores the message and notifies staff, with no job. In `RESOLVED` it first reopens the conversation (`REOPENED`), then continues as in `AI`.

### Jobs

- `core.jobs` is the queue of business-service (T36, worker T39); `agent.jobs` is agent-service's own (T50). Both work the same way, except that agent-service runs one job at a time (see [Knowledge base](#knowledge-base-t50)).
- A worker (`JobWorker`, T39) polls every second for `status = 'PENDING' AND run_at <= now()` with `FOR UPDATE SKIP LOCKED`, sets `RUNNING`, `locked_at` and `attempts + 1` and commits, runs the job outside any transaction (the agent call never holds a connection or a lock), and then sets `DONE`, or `PENDING` with a later `run_at` (backoff: 5 s, then doubling) and `last_error` (error type only, e.g. `ServiceUnavailable 503`, never message text or a response body), or `FAILED` after the last attempt (3 in total). Each instance runs up to 4 jobs at a time, each on a virtual thread.
- A job left `RUNNING` with a `locked_at` older than 5 minutes (the instance died) goes back to `PENDING`; its attempt still counts.
- `AI_REPLY` jobs of one conversation run one at a time, in order: the worker skips a job whose conversation already has a `RUNNING` `AI_REPLY` or an older `PENDING` one (for example waiting for its retry).
- **Handlers:** each type has one `JobHandler` bean (`type()`, `run(job)`, and an optional `onFinalFailure(job)` that runs once before the job becomes `FAILED`). Returning normally means `DONE`, also when there was nothing to do; an exception is a failed attempt. A handler must be safe to run twice. `AiReplyJobHandler` (T39) skips a job when `ConversationService.isAiAnswering` is false (state not `AI`) and, on final failure, calls `ConversationService.failAiReply` (`AI_FAILED`, `AI` → `WAITING`; nothing when the state already changed). `AutoAckJobHandler` (T41) runs `AUTO_ACK`.
- **Staff messages:** an `AI_REPLY` sends the `HUMAN` messages the customer saw (not internal notes) since the conversation's last `AI` message, oldest first, with the author's name (at most the last 50, bodies cut at 4,000 characters).
- **Configuration** (`tendwise.jobs.*` in `application.yml`): `worker-enabled` (`JOB_WORKER_ENABLED`, default `true`; tests turn it off and call the worker directly), `poll-interval`, `max-attempts`, `retry-delay`, `stale-after`, `max-concurrent`. agent-service is reached at `AGENT_SERVICE_URL` (compose: `http://agent-service:8000`) with a 60-second read timeout.
- **Events (T40):** `AiReplyJobHandler` marks where `ai.typing` is sent and where the stored `AI` or `AI_FAILED` message is published after its commit.
- Types: `AI_REPLY` (T39), `AUTO_ACK` (T41), `SEND_EMAIL` (T48, real provider T69), `USAGE_ROLLUP` (T59). `agent.jobs`: `INDEX_DOCUMENT` (T50), `REGENERATE_CATALOG` (T52). A new type is added by the task that needs it and listed here.

## Agent graph

`agent-service/app/agent/graph.py` wires one node per file in `app/agent/nodes/` (each a plain function `run(state, deps) -> partial state`). `AgentService.handle_turn(conversation_id, business, message)` in `app/agent/service.py` runs it once per customer message.

```
guard_input ─┬─ blocked ───────────────────────────────────────────────┐
             └─ classify_intent → route ─┬─ retrieve → answer_with_context ─┤
                                         ├─ ask_or_call_tool ───────────────┤
                                         ├─ create_handoff ─────────────────┤
                                         └─ answer_generic ─────────────────┤
                                                                            ▼
                                      guard_output → remember_turn → log_turn → END
```

- **guard_input / guard_output:** deterministic checks before and after the agent, see [Guardrails](#guardrails). If guard_input sets `blocked`, the graph skips straight to guard_output with the fixed refusal guard_input set.
- **log_turn:** placeholder, filled in by T12 (turn logging).
- **classify_intent:** the T09 classifier; also updates `negative_streak` and remembers any order code or phone digits the customer gave. Its JSON has `intent`, `confidence`, `sentiment`, `language`, `order_code`, `phone_last4` and `search_query` (catalog questions only, see [Retrieval](#retrieval)). For `order_status`, when the model misses phone digits written next to a cue word ("last digits 0102", "final 0102", "termina en 0102"), a regex in `intent.py` fills them in; digits inside an order code or a longer number are ignored.
- **route:** deterministic code, checked in this order: confidence < 0.6 → handoff `LOW_CONFIDENCE`; `human_request` → `HUMAN_REQUEST`; `complaint` → `COMPLAINT`; negative sentiment on 2 consecutive turns → `NEGATIVE_SENTIMENT`; otherwise `catalog_question` → retrieve, `order_status` → ask_or_call_tool, `other` → answer_generic.
- **retrieve → answer_with_context:** top-k chunks of the business; the LLM answers only from them and the reply lists their sources. No chunks → a fixed "I don't have that information, want a person?" reply, without an LLM call.
- **ask_or_call_tool:** asks (fixed text) for whatever is missing of order code and last 4 phone digits; with both, calls `get_order_status` through `BusinessGateway`. Not found → a fixed "could not find an order with that code and phone" reply; found → the LLM phrases the status from the tool data.
- **create_handoff:** the LLM writes a staff-facing summary (max 2,000 characters, in the business's default language), the gateway calls `POST /handoffs`, and the customer gets a fixed "a person will continue" reply.
- **answer_generic:** greetings and anything else; stays in the business's scope and declines off-topic requests.
- **remember_turn:** appends the message and the final reply to `history` (last 40 messages). Blocked messages are not remembered.
- Replies are in the classified language (`en`, `es`, `pt`). Prompts live in `app/prompts/*.md`; fixed replies in `app/agent/replies.py`. Tool calls are recorded in the state with the phone masked (`****1234`).

### Memory

- State that **persists across turns** of a conversation: `history`, `negative_streak`, `pending_order_code`, `pending_phone_last4` (the last two are cleared once `get_order_status` has been called). Every other field (`intent`, `chunks`, `reply`, `sources`, `handoff`, `blocked`, `guard_reason`, `blocked_language`, `tool_calls`, `llm_usage`, ...) is reset at the start of each turn.
- LangGraph saves a checkpoint after each node, keyed by `thread_id = conversation_id`. Production uses `PostgresSaver` (`langgraph-checkpoint-postgres`) on a connection with `search_path=agent`, so its tables `agent.checkpoints`, `agent.checkpoint_blobs`, `agent.checkpoint_writes` and `agent.checkpoint_migrations` live in the `agent` schema; they are created and upgraded by the library's `setup()` at startup (not by our SQL migrations). Tests use the in-memory saver.

### Guardrails

`app/guardrails/` holds plain Python checks (regexes and short word lists, no LLM call), used by the two guard nodes. They are free, instant and fully unit-tested; anything subtler is left to the system prompts, which keep the model in the business's scope.

**guard_input** (`app/guardrails/input.py`, checked in this order, first hit wins):

| Check | Rule | `guard_reason` |
|---|---|---|
| Empty | nothing left after trimming | `length:empty` |
| Length | more than 1,000 characters after trimming | `length:too_long` |
| Prompt injection | patterns in `injection.py`, en/es/pt | `injection:<rule>` |
| Abuse | short list of insults in `abuse.py`, en/es/pt | `abuse` |

- Injection rules: `ignore_previous` ("ignore all previous instructions"), `disregard_rules` ("disregard your rules", "olvida tus reglas", "esqueça suas regras"), `role_change` ("you are now…", "act as a…", "finja que você é…"), `system_prompt`, `reveal_prompt` ("show me your instructions", "mostre seu prompt"), `developer_mode`, `jailbreak` (including an all-caps "DAN"), `role_tag` (`<system>`, `[INST]`, `<<SYS>>`, `<|im_start|>`, a `### system` line).
- The message is normalised first (lowercase, accents and invisible characters removed), so "INSTRUÇÕES" and "instrucoes" hit the same pattern. Patterns are narrow on purpose: "ignore the mushrooms on my pizza" or "what's your system for deliveries?" pass.
- The abuse list holds insults aimed at someone ("fuck you", "eres un idiota", "vai se foder"), not every swear word: an angry complaint must reach a person through the handoff route, not be blocked.
- A blocked message gets a fixed refusal from `app/guardrails/replies.py` ("please send a shorter message", "I can only help with questions about <business>", "let's keep it respectful"). It never reached the classifier, so the reply language is guessed by `language.py` from common en/es/pt words, falling back to the business's `default_language`. guard_input stores that guess in `blocked_language`, and the chat response and the turn log report it as the turn's `language`.
- `guard_reason` is stored in the state (per turn) for the turn log; only the rule name is ever logged, never the message.

**guard_output** (`app/guardrails/output.py`, runs on every reply, blocked ones too):

- Phone numbers (7 or more digits, with spaces, dots, dashes, parentheses or a leading `+`) become `****` + the last 4 digits (`****0102`), the same `mask_phone_last4` format used for stored tool calls. Prices (`$14.00`, `R$ 199,90`), times (`11:00–22:00`), ISO dates, order codes (`JP-1042`) and short numbers are left alone.
- E-mail addresses become `****@domain`.
- Replies over 1,200 characters are cut at the last full sentence and end with " …".
- This masks the business's own phone and e-mail too, if the model copies them from a document: the spec puts no phone number in a reply, and a person can share contacts after a handoff.
- Customer names never reach the agent: business-service's order status response has no customer data (`code`, `status`, `fulfilment`, `eta`, `updated_at`), and a test fails if a field is added.

### BusinessGateway

`app/gateways/business_gateway.py`: the `BusinessGateway` interface (`get_business`, `get_order_status`, `create_handoff`) and `HttpBusinessGateway`, which calls business-service with `X-Api-Key` and a 5-second timeout, maps its camelCase JSON to snake_case models and returns `None` on 404. httpx request logging is limited to warnings, because the order status URL carries `phoneLast4`.

### Planned agent changes (control panels)

- **`POST /internal/v1/replies`** (T37) runs the same graph as `/api/v1/chat`. `staff_messages` are appended to `history` before the turn, so the AI does not contradict what a person said.
- **Assistant settings** (T54): `assistantSettings` from `GET /api/v1/businesses/{slug}` (name, tone, greeting, extra instructions, languages) go into the prompts inside a delimited, length-capped section below the system rules. Outside opening hours the handoff reply says when the team is back and offers to collect a contact. The eval suite gets cases where merchant instructions try to break the rules.
- **Knowledge gaps** (T58): the `retrieve` node records catalog questions with no chunk above the threshold.
- **Knowledge base from the database** (T50): documents are edited in the panel and re-indexed per business by an `INDEX_DOCUMENT` job, so saving is instant.
- **Catalog document** (T52): regenerated from `GET /api/v1/businesses/{slug}/catalog` whenever the catalog changes, so the catalog has one source of truth. **Deferred:** how business-service tells agent-service that the catalog changed is proposed in T52.
- **Playground** (T51): a chat from the knowledge page, stored with `channel = playground`. Its `create_handoff` node does not call business-service (there is no `core` conversation); it only reports the handoff it would have created.
- **Model per plan:** **deferred**, no task yet. `plans.features.model` holds the model id; how the agent receives it is proposed when a task is created.
- **Copilot** (T72): `POST /internal/v1/suggest-reply` drafts an answer for staff from the transcript and the knowledge base.

## Observability

Every turn answered by `AgentService.handle_turn` (so every `/chat` call) writes **one row** to `agent.turns` (migration `002_conversations_turns.sql`). The last graph node, `log_turn`, builds a `TurnRecord` from the state and hands it to `deps.turn_logger`:

- `PostgresTurnLogger` (`app/observability/turn_log.py`, passed in by `create_agent_service`) inserts the conversation row (`INSERT … ON CONFLICT (id) DO NOTHING`: business slug, channel `web`, the language of its first turn) and the turn row **in one transaction**. The chat API never manages conversations itself.
- `AgentDeps.turn_logger` defaults to `NoOpTurnLogger`, so tests and fakes need no database.
- Logging is best effort: if it fails, the customer still gets the reply and `log_turn` logs a warning with only the error type (its message could hold personal data).

What one turn row stores:

| Column | Value |
|---|---|
| `role` | always `assistant`: one row per turn, holding the final reply |
| `content` | the reply after `guard_output`, phone-like numbers (9+ digits) masked as `****1234` (`app/agent/privacy.py`) |
| `intent` | the classified intent; `NULL` when `guard_input` blocked the message |
| `tool_calls` | JSONB list of `{name, args, outcome}`, phone digits already masked |
| `prompt_tokens`, `completion_tokens` | summed over every LLM call of the turn (`state["llm_usage"]`) |
| `latency_ms` | from `started_at` (set when the turn starts) to `log_turn` |
| `cost_usd` | estimated cost (below) |
| `model` | the model of the turn's last LLM call; `NULL` when the turn made none (fixed replies) |
| `blocked`, `handoff` | flags, so the report needs no JSON parsing. **Additions to spec section 7** |

**Cost estimate:** `app/observability/pricing.py` keeps USD prices per million input/output tokens for the models we use (Anthropic first-party prices, checked 2026-11-13: Haiku 5.5 $0.10 / $0.50, Sonnet 5.5 $2 / $10, Opus 5.5 $4 / $20; OpenAI Standard-tier prices up to 272K input tokens, checked 2026-11-13: gpt-6-luna $0.10 / $0.50, gpt-5.6-luna $0.20 / $1.20, gpt-5.4-mini $0.75 / $4.50, a dated snapshot such as `gpt-5.4-mini-2026-03-17` is priced like its alias; reasoning tokens are billed as output). Each LLM call costs `prompt_tokens × input price + completion_tokens × output price`; the turn's cost is the sum. A model missing from the table costs 0 and logs one warning; add it to the table when you switch models. It is an estimate for the report, not the invoice (prompt caching and Haiku's price above 100K prompt tokens are ignored).

**Report:** `app/observability/report.py` aggregates in SQL (`percentile_cont` for latencies) and `scripts/report.py` prints a text table: turns, conversations, latency p50/p95, total cost, average cost per conversation, handoff rate, blocked rate and the intent distribution (blocked turns count as `none`).

```bash
cd agent-service && uv run python scripts/report.py
uv run python scripts/report.py --business joes-pizza --since 2026-11-01
docker compose exec agent-service python scripts/report.py
```

**Retention** (spec section 9): `uv run python scripts/report.py --purge` deletes the conversations created more than `CONVERSATION_RETENTION_DAYS` (default 90) days ago, their turns (`ON DELETE CASCADE`) and their LangGraph checkpoints (`delete_thread`), which hold the message history. Run it on a schedule (e.g. a daily cron) in production.

## API contracts

### business-service

Prefix `/api/v1`. Every call requires the `X-Api-Key` header.

| Method | Route | Description |
|---|---|---|
| GET | `/businesses/{slug}` | Business info: `slug`, `name`, `category`, `defaultLanguage`, `timezone`, `openingHours` (JSON object, e.g. `{"mon": [], "tue": [{"open": "11:00", "close": "22:00"}]}`). 404 if the business does not exist |
| GET | `/businesses/{slug}/catalog` | Catalog items ordered by name: `id`, `name`, `description`, `price` (number, 2 decimals), `currency`, `available`, `kind` (`PRODUCT`, `SERVICE`). Unavailable items are included with `available: false`. 404 if the business does not exist |
| GET | `/orders/{code}/status?businessSlug=joes-pizza&phoneLast4=1234` | Status, fulfilment and ETA (no customer data). Returns 404 if the business or code does not exist, the order belongs to another business, or the phone does not match, without telling the cases apart. 400 if `phoneLast4` is not 4 digits |
| POST | `/handoffs` | Create handoff. Body: `businessSlug`, `conversationId` (UUID), `reason` (`COMPLAINT`, `HUMAN_REQUEST`, `NEGATIVE_SENTIMENT`, `LOW_CONFIDENCE`), `summary` (max 2000 chars). 201 with `Location: /api/v1/handoffs/{id}` and the handoff, status `OPEN`; the conversation moves to `WAITING` (T41). 404 if the business does not exist, 400 if the body is invalid, 409 if the conversation is not in `AI` (Problem Details with `state` and `assigneeUserId`) |
| GET | `/handoffs?businessSlug=joes-pizza&status=OPEN` | Handoffs of the business, oldest first. `status` is optional (absent = all). 404 if the business does not exist, 400 on an unknown status |
| PATCH | `/handoffs/{id}?businessSlug=joes-pizza` | Update status. Body: `status` (any of `OPEN`, `IN_PROGRESS`, `RESOLVED`; no state machine in the MVP). 200 with the handoff. 404 if the id does not exist or belongs to another business, without telling the cases apart |

Errors follow RFC 7807 (Problem Details). Docs via springdoc/Swagger at `/swagger-ui` (public; use "Authorize" to send the key).

- Missing or wrong `X-Api-Key` on a service route (`/api/v1/businesses/**`, `/api/v1/orders/**`, `/api/v1/handoffs/**`) → 401. The key comes from `BUSINESS_SERVICE_API_KEY`; the service does not start without it.
- `businessSlug` on the order status and handoff routes keeps tenant isolation: the agent can only read and change data of the business it is talking for.
- Handoff responses: `id`, `conversationId`, `reason`, `summary`, `status`, `createdAt`, `updatedAt`.

Planned changes to these service routes (they stay at the same paths, for the agent's tools):

- `GET /businesses/{slug}` adds `assistantSettings` (T53, read by the agent from T54): `name`, `tone`, `greeting`, `extraInstructions`, `outOfHoursMessage` (each `null` when not set) and `languages` (all of `en`, `es`, `pt` until the owner chooses). The other fields are unchanged; agent-service ignores the new one until T54.
- `POST /handoffs` (T41): when the conversation exists in `core.conversations` for that business, the same transaction moves it to `WAITING` with a `SYSTEM` message (`HANDOFF_CREATED`, with `reason` and `handoffId`) and queues the `AUTO_ACK` job.
- **Temporary rule until T42:** when the conversation is not in `core.conversations` for that business (the demo chat still creates its conversations in agent-service only), the handoff is created as in v0.1.0, with no state change and no auto-ack, so the demo's handoff keeps working. A conversation of another business is never changed. `HandoffConversationTest` pins this rule.
- `POST /handoffs` (planned: T42): once the demo chat uses the public API, an unknown conversation answers 404 (same as an unknown business), and the tests that pin the temporary rule change with it.
- `PATCH /handoffs/{id}` is removed in T44: from then on a handoff's status follows its conversation, and no caller uses the route (the agent only creates handoffs).
- **As built (T32):** the `X-Api-Key` filter covers only the service routes (`/api/v1/businesses/**`, `/api/v1/orders/**`, `/api/v1/handoffs/**`; `ApiKeyFilter.SERVICE_ROUTES`, also used by `SecurityConfig`). A session cookie never opens them, and the key opens nothing else: `/api/v1/panel/**` and `/api/v1/admin/**` use the session cookie. Any other `/api/` path is denied.

### agent-service

| Method | Route | Description |
|---|---|---|
| POST | `/api/v1/chat` | In: `conversation_id` (optional UUID; absent or `null` starts a conversation), `business_slug`, `message` (max 4,000 chars; an empty message or one over 1,000 gets guard_input's polite reply with 200). Out: `conversation_id`, `reply`, `intent` (`null` when guard_input blocked the message), `language`, `sources[]` (`title`, `source_path`, `section`), `handoff` (bool). 404 unknown business, 409 conversation of another business, 422 invalid body, 429 rate limit, 503 business-service or LLM unavailable |
| GET | `/health` | Service and database health |
| POST | `/api/v1/admin/ingest` | Re-index `data/businesses` (admin key required) |

- `POST /api/v1/chat` errors are JSON `{"detail": "..."}` and never contain exception text: `404` `Business not found`; `409` `Conversation belongs to another business` (tenant isolation: the business slug stored in the conversation's checkpoint must match the request); `429` when a conversation sends more than `CHAT_RATE_LIMIT_MESSAGES` (20) messages in `CHAT_RATE_LIMIT_WINDOW_SECONDS` (600) — an in-memory sliding window per process, to move to a shared store such as Redis when there is more than one process; `503` `Business service unavailable` (unreachable, 5xx, or `BUSINESS_SERVICE_API_KEY` unset) or `Assistant temporarily unavailable` (LLM provider error, the selected provider's key — `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` — unset, or the agent could not be built at startup). The agent service is built once at startup; if that fails, the app still starts and `/health` still works.

- `POST /api/v1/admin/ingest` requires the `X-Admin-Key` header (compared in constant time with `ADMIN_API_KEY`): 401 if it is missing or wrong, 503 if `ADMIN_API_KEY` is not configured. 200 body: `{"added", "updated", "skipped", "deleted"}` (document counts).

### Planned API families (control panels)

Five families, kept apart by path prefix, so each prefix has a single kind of authentication:

| Family | Prefix | Service | Authentication | Section |
|---|---|---|---|---|
| Public (website chat) | `/public/v1` | business-service | Publishable key to start, then the conversation token | [Public API](#public-api-planned) |
| Panel (business staff) | `/api/v1/panel/{slug}` | business-service; knowledge routes in agent-service | Session cookies (JWT) + CSRF header | [Panel API](#panel-api-planned) |
| Admin (operator) | `/api/v1/admin` | business-service | Session cookies, `platform_role = ADMIN` | [Admin API](#admin-api-planned) |
| Auth | `/auth` | business-service | None (login), or the refresh cookie | [Auth API](#auth-api-planned) |
| Internal (service to service) | `/internal/v1` | agent-service | `X-Api-Key` | [Internal API](#internal-api-planned) |

The v0.1.0 service routes (`/api/v1/businesses`, `/api/v1/orders`, `/api/v1/handoffs` with `X-Api-Key`) stay as they are for the agent's tools. Errors follow RFC 7807 in business-service and `{"detail": "..."}` in agent-service, as today.

#### Public API (planned)

business-service. To start, the business's **publishable key** (public by design; protected by allowed domains and rate limits). After that, the **conversation token** in `Authorization: Bearer <token>`. The token never goes in a URL. Requests from an origin that is not in `widget_settings.allowedDomains` are rejected (CORS, and `frame-ancestors` on the widget page).

| Method | Route | Description | Planned |
|---|---|---|---|
| POST | `/public/v1/conversations` | Body: `publishableKey`, `language` (optional). 201 with `conversationId`, `token` (shown only here), `greeting`. 403 for an origin that is not allowed | T38 |
| POST | `/public/v1/conversations/{id}/messages` | Body: `body` (max 4,000 characters, like `/api/v1/chat`). 202 with the stored message. 404 for an unknown conversation or a wrong token (not told apart). 429 on rate limit (per conversation and per IP) | T38 |
| GET | `/public/v1/conversations/{id}/messages?after=` | History after a message id, oldest first (page reload, reconnect). Never returns `internal` messages | T38 |
| GET | `/public/v1/conversations/{id}/events` | SSE stream of this conversation (see [Real-time](#real-time-planned-t40)) | T40 |
| POST | `/public/v1/conversations/{id}/contact` | Body: `email` or `phone`, and `consent: true` (400 without it). The customer leaves a contact so staff can answer later | T41 |
| POST | `/public/v1/conversations/{id}/satisfaction` | Body: `rating` (`UP`, `DOWN`), `comment` (optional). One per conversation | T61 |

- Message fields: `id`, `sender`, `authorName` (first name of the staff member, `HUMAN` only), `body`, `createdAt`. No `metadata`, no user ids.
- **As built (T38):** package `dev.ocaina.tendwise.publicapi`. `allowedDomains` holds origins (`https://shop.example.com`), compared with the `Origin` header (case and a trailing `/` ignored). CORS preflight is open for `/public/v1/**` (no credentials); the per-business check answers `403` (problem `Origin not allowed`) because the business is only known from the key or the token. A request without `Origin` (not from another website) is let through; the key, token and limits still apply. A missing, malformed or wrong token, an unknown id and another business's conversation all give the same `404`. Limits (`tendwise.public-api.*`): 20 customer messages per conversation in any 10 minutes (counted in `core.messages` under the conversation's row lock), 30 POSTs per IP per minute (in memory; the IP comes from `X-Forwarded-For` of a private-network proxy). History returns at most 100 messages per call; the widget asks again with the last id. The greeting is a fixed text per language until T53 (`assistant_settings.greeting`); it is not stored as a message.
- A suspended business (T62) answers the start call with a "temporarily unavailable" problem the widget shows as is.
- The widget reads the SSE stream with `fetch` and the `Authorization` header instead of `EventSource`, because it runs in an iframe on a third-party site.

#### Panel API (planned)

Session cookies ([Security](#security-planned)). Every route has `/{slug}`, and a guard checks the user's membership and role **before** the controller runs (T32). A business the user does not belong to answers `404`, the same as one that does not exist; a member without the needed role gets `403`. "AGENT" below means `AGENT` or `OWNER`.

business-service:

| Method | Route | Role | Planned |
|---|---|---|---|
| GET | `/api/v1/panel/{slug}/conversations?state=&assignee=&q=&cursor=` | AGENT | T43 |
| GET | `/api/v1/panel/{slug}/conversations/{id}` (with messages, handoffs, satisfaction) | AGENT | T43 |
| POST | `…/conversations/{id}/takeover` (409 if someone else has it) | AGENT | T44 |
| POST | `…/conversations/{id}/messages` (body, `internal`) | AGENT (must be the assignee, unless `internal`) | T44 |
| POST | `…/conversations/{id}/assign` (`userId`, a member of the business) | AGENT | T44 |
| POST | `…/conversations/{id}/release` (back to AI) | AGENT | T44 |
| POST | `…/conversations/{id}/resolve` | AGENT | T44 |
| POST | `…/conversations/{id}/read` | AGENT | T44 |
| POST | `…/conversations/{id}/typing` (no body; sends `staff.typing`, stores nothing; at most one per 3 seconds) | AGENT (assignee) | T44 |
| GET | `/api/v1/panel/{slug}/events` (SSE for the whole business) | AGENT | T40 |
| CRUD | `/api/v1/panel/{slug}/canned-replies` | AGENT read, OWNER write | T48 |
| CRUD | `/api/v1/panel/{slug}/catalog` | OWNER | T52 |
| GET, PATCH | `/api/v1/panel/{slug}/orders` (list, search, update status) | OWNER | T57 |
| GET, PUT | `/api/v1/panel/{slug}/settings` (profile and opening hours from `businesses`, plus `assistantSettings`, `handoffSettings`, `widgetSettings`) | OWNER | T53 |
| CRUD | `/api/v1/panel/{slug}/team`, `/api/v1/panel/{slug}/invitations` | OWNER | T34 |
| CRUD | `/api/v1/panel/{slug}/api-keys` | OWNER | T57 |
| GET | `/api/v1/panel/{slug}/dashboard?from=&to=` (from `usage_daily` and `handoffs`) | OWNER | T60 |

- **As built (T53):** package `dev.ocaina.tendwise.settings`. `GET` and `PUT /settings` (OWNER only) use one body: `name`, `category`, `defaultLanguage`, `timezone`, `openingHours`, `assistantSettings`, `handoffSettings` (`autoAckMinutes`, 1–60), `widgetSettings` (`color` `#rrggbb`, `position` `bottom-right` or `bottom-left`, up to 20 `allowedDomains`). `PUT` replaces everything; other keys already in `handoff_settings` (e.g. `waitingAlertMinutes`, T48) are kept. Caps: assistant `name` 60, `tone` 200, `greeting` 500, `extraInstructions` 1,000, `outOfHoursMessage` 500 characters. Checks: an IANA region time zone, days `mon`–`sun` with up to 4 `HH:mm` intervals each (close ≠ open; a close before open ends the next day), languages without repeats that include `defaultLanguage`, and allowed domains that are origins (`http`/`https`, host, optional port; no path, query or user). An unknown field, a wrong type or any failed check answers `400` (problem `Invalid request`) with `errors: [{field, message}]`, e.g. `openingHours.mon[0].open`. The widget greeting still uses the fixed texts; `assistantSettings.greeting` is not used by the public API yet.

agent-service (verifies the same JWT with the JWKS, and the CSRF header):

| Method | Route | Role | Planned |
|---|---|---|---|
| GET, POST | `/api/v1/panel/{slug}/documents` | AGENT read, OWNER write | T50 |
| GET, PUT, DELETE | `/api/v1/panel/{slug}/documents/{id}` | AGENT read, OWNER write | T50 |
| POST | `/api/v1/panel/{slug}/documents/reindex` | OWNER | T50 |
| GET | `/api/v1/panel/{slug}/knowledge-gaps` | OWNER | T58 |
| POST | `/api/v1/panel/{slug}/playground` (chat with `channel = playground`, left out of the metrics) | OWNER | T51 |

- **As built (T32):** package `dev.ocaina.tendwise.panel`. Spring Security requires a valid access token on `/api/v1/panel/**` (401 otherwise). Then `PanelGuard`, a Spring MVC interceptor, runs before the controller: it takes the slug from the path, looks for it in the token's `memberships`, and loads the business by slug; a missing membership or business gives the same `404` problem (`Business not found`). A route or controller marked `@OwnerOnly` answers `403` to an `AGENT`; unmarked routes are open to every member. The controller declares a `CurrentMembership` parameter (`businessId`, `slug`, `role`) and uses its `businessId` in every query. Roles come from the token, so a role change or removal takes effect within the token's 15 minutes. `PanelTenantIsolationTest.ROUTES` lists every panel route; each panel task appends its routes there, and the test fails if a mapped panel route is missing.
- **As built (T43):** package `dev.ocaina.tendwise.inbox`, open to every member. `GET …/conversations` takes `state` (`AI`, `WAITING`, `HUMAN`, `RESOLVED`; absent = all), `assignee` (`me` or a user id), `q` (case-insensitive text in the non-`SYSTEM` messages, internal notes included; at most 200 characters; `%` and `_` are plain text), `cursor` and `limit` (1–100, default 30). The tabs are `state=WAITING`, `state=HUMAN&assignee=me`, `state=AI` and `state=RESOLVED`. Order: `last_message_at` desc, then `id` desc; `cursor` is the opaque `nextCursor` of the previous page (`last_message_at` + `id` of its last row; `null` on the last page). Response: `items`, `nextCursor`, `counts`. Each item: `id`, `state`, `channel`, `language`, `assignee` (`id`, `name`) or null, `lastMessage` (`sender`, `preview` = first 120 characters, `internal`) of the newest non-`SYSTEM` message or null, `lastMessageAt`, `unreadCount` (`unread_by_staff`), `handoffReason` of the handoff that is `OPEN` or `IN_PROGRESS`, `waitingSince` (time of the `HANDOFF_CREATED` or `AI_FAILED` message, only in `WAITING`). `counts` holds the badges `waiting`, `mine` (`HUMAN` assigned to the caller) and `ai`, each `conversations` and `unread` (how many have unread messages); they ignore the filters. One SQL query per page (LATERAL joins, no query per row) and one for the counts. `GET …/conversations/{id}`: the conversation fields plus `createdAt`, `resolvedAt`, `messages` (all, oldest first, internal included: `id`, `sender`, `authorUserId`, `authorName`, `body`, `internal`, `metadata`, `createdAt`) and `handoffs` (`id`, `reason`, `summary`, `status`, `createdAt`, `updatedAt`); `satisfaction` is added with its table (T61). Another business's conversation gives `404` (`Conversation not found`). A bad `cursor`, `assignee`, `limit` or `q` gives `400`. Reading does not mark the conversation read (`POST …/read`, T44).
- **As built (T35):** package `app/auth` in agent-service. A route takes `Depends(require_panel_role("AGENT" | "OWNER"))` and gets the user and the business slug. Checks, in business-service's order: `X-CSRF-Token` equal to the `tendwise_csrf` cookie on `POST`/`PUT`/`PATCH`/`DELETE` (403), a valid `tendwise_access` token (401), the slug among the token's memberships (404), the role (403; `OWNER` includes `AGENT`). The token must be `EdDSA`, signed by a key from `AUTH_JWKS_URL` (default `http://business-service:8080/auth/jwks.json`), with `iss` = `tendwise-business-service` and an unexpired `exp`; it has no `aud` (business-service sets none, and a token that has one is refused). The keys are cached for 5 minutes and fetched again early for an unknown `kid`, at most once every 30 seconds. If no keys can be fetched the route answers 503. A platform admin gets no business through this guard (support mode is T64).
- **As built (T34):** package `dev.ocaina.tendwise.team`, all `@OwnerOnly`. `GET …/team` lists members (`userId`, `name`, `email`, `role`, `joinedAt`) by name; `PATCH …/team/{userId}` (body `role`) and `DELETE …/team/{userId}` (`204`) answer `404` for a user who is not a member of this business and `409` (problem `Last owner`) when the business would have no `OWNER` left; the business's owner memberships are locked (`SELECT … FOR UPDATE`) first, so two owners demoting each other at once leave one. Removal deletes only the membership, never the user. Refresh tokens are **not** revoked: they have no `business_id`, and revoking them would log the user out of their other businesses; every refresh reads the memberships again, so a removed or demoted member loses the access within the access token's 15 minutes. `GET …/invitations` lists the pending ones (`id`, `email`, `role`, `expiresAt`; never the token); `POST …/invitations` (body `email`, `role`) answers `201`, `409` (`Already a member`) for an email that is already in the team, and replaces a pending invitation for the same email (the old link stops working); `DELETE …/invitations/{id}` revokes a pending one by deleting its row (`404` for another business's id). The token is 32 random bytes; only its SHA-256 is stored.
- **As built (T50):** `app/api/documents.py`, JSON in camelCase like business-service. A document is `id`, `sourcePath`, `title`, `source` (`FILE`/`EDITOR`), `status` (`PENDING`/`INDEXED`/`FAILED`), `characters`, `updatedAt`, `updatedBy`; `GET …/documents/{id}`, `POST` and `PUT` add `content`, the list leaves it out. `POST` and `PUT` take `{"title", "content"}`; `POST` answers `201`, `PUT` `200`, `DELETE` `204`, `POST …/reindex` `202` with `{"queued": n}` (every document of the business set `PENDING` and queued). A document id of another business answers `404` `Document not found`, like an unknown id. An unreachable database answers `503`. Limits and indexing: [Knowledge base](#knowledge-base-t50). nginx and the Vite dev proxy send `/api/v1/panel/{slug}/documents…` to agent-service ahead of the business-service `/api/v1/panel/` prefix.
- **Deferred:** the orders integration API that a client's own system calls with a business API key (`api_keys`, scope per key) is proposed in T57; only the panel routes above are fixed.
- Support mode (T64) can call every `GET` above and nothing else.
- Dashboard and overview numbers use the metric definitions in [panels-plan.md, section 10](panels-plan.md#10-metrics-definitions).

#### Admin API (planned)

business-service, `platform_role = ADMIN` (403 for anyone else; from T68 also a verified TOTP).

**As built (T32):** `/api/v1/admin/**` requires a valid access token (401) whose `platformRole` is `ADMIN` (403 otherwise; the token filter gives the operator `ROLE_ADMIN`). No admin endpoints exist yet.

| Method | Route | Planned |
|---|---|---|
| GET, POST | `/api/v1/admin/businesses` (create sends the owner invitation) | T62 |
| GET, PATCH | `/api/v1/admin/businesses/{slug}` (status and plan; limits come from the plan) | T62 |
| POST | `/api/v1/admin/businesses/{slug}/support-session` (read-only, 30 min, audited) | T64 |
| CRUD | `/api/v1/admin/plans` | T62 |
| GET | `/api/v1/admin/overview?from=&to=`, `/api/v1/admin/usage?groupBy=business` | T65 |
| GET | `/api/v1/admin/alerts` | T65 |
| GET | `/api/v1/admin/audit-log` | T64 |

#### Auth API (planned)

business-service.

| Method | Route | Description | Planned |
|---|---|---|---|
| POST | `/auth/login` | Body: `email`, `password`. Sets the access, refresh and CSRF cookies; body as `/auth/me`. 401 with one generic message for every failure; locked accounts get the same answer | T31 |
| POST | `/auth/logout` | Revokes the refresh token family and clears the cookies | T31 |
| POST | `/auth/refresh` | Rotates the refresh token and issues a new access token. A reused refresh token revokes its family (401) | T31 |
| GET | `/auth/me` | User (`id`, `email`, `name`), `platformRole`, `memberships` (`slug`, `name`, `role`) | T31 |
| GET | `/auth/jwks.json` | Public key(s) that sign the access token (JWKS), read by agent-service | T31 |
| POST | `/auth/invitations/accept` | Body: `token`, `name`, `password`. Creates the user or adds the membership. Expired or used token: 400 | T34 |
| POST | `/auth/password-reset` | Body: `email`. Always 202, whether the email exists or not | T69 |
| POST | `/auth/password-reset/confirm` | Body: `token`, `password` | T69 |
| POST | `/auth/2fa/setup` | Starts TOTP setup (secret and QR URI) for the logged-in user | T68 |
| POST | `/auth/2fa/verify` | Body: `code`. Confirms setup, or completes a login that needs 2FA | T68 |

Invitation and reset tokens travel in the email link to a web page, which sends them in the request **body**, never in an API path.

**As built (T34), `POST /auth/invitations/accept`:** public and exempt from the CSRF header (it reads no cookies, and the invitee may have no session). The invitation row is locked while it is accepted, so a token works once. An unknown, expired, revoked or used token all answer the same `400` (problem `Invalid invitation`). With no account for the email, `name` (max 120) and `password` (8 to 1,024 characters) are required, or `400` (`Account details required`) and the invitation stays pending. An existing user just gets the membership: `name` and `password` are ignored and the password is never changed; someone who is already a member keeps their role. `200` body: `slug`, `businessName`, `role`, `accountCreated`. It sets no cookies: the web app sends the user to `/login` (a logged-in user calls `/auth/refresh` to get the new business in the token). The email link is `<WEB_URL>/invitations/accept#token=<token>` (the token in the fragment, so it never reaches a server log); that web page is not built yet (T55).

**Email (T34):** the `EmailSender` port (`dev.ocaina.tendwise.email`) has one method per kind of email. Until T69 its only adapter is `LogEmailSender`, which sends nothing and logs `invitation email sent to j***@domain (link: …)`: the address is masked and the link is cut before `#token=`. Only with `EMAIL_LOG_LINKS=true` (`tendwise.email.log-links`, default `false`) does it log the full link, so you can open it on your own machine; never turn it on in a shared or production environment.

Details of the T31 routes (package `dev.ocaina.tendwise.auth`, Spring Security with two small filters of our own):

- **Cookies.** All `SameSite=Lax` and `Secure` (`AUTH_COOKIE_SECURE=false` turns `Secure` off for plain http that is not localhost). Tokens never appear in a response body.

  | Cookie | Holds | HttpOnly | Path | Max-Age |
  |---|---|---|---|---|
  | `tendwise_access` | access token (JWT) | yes | `/` | 15 minutes |
  | `tendwise_refresh` | refresh token (32 random bytes, base64url) | yes | `/auth` | 30 days |
  | `tendwise_csrf` | CSRF token (32 random bytes, base64url) | no (the web app reads it) | `/` | 30 days |

- **Access token claims:** `sub` (user id), `platformRole` (`ADMIN`, absent for business staff), `memberships` (`[{"slug", "role"}]`), `iss` = `tendwise-business-service`, `iat`, `exp`. Header: `alg` = `EdDSA`, `kid` = the key's JWK thumbprint (RFC 7638). No email or name.
- **Signing key:** `AUTH_JWT_PRIVATE_KEY`, a base64 PKCS#8 Ed25519 private key (PEM also accepted); the public key is derived from it. The service does not start without it. Each environment generates its own (command in `.env.example`); it is never committed. Tests generate one in memory.
- **JWKS:** `GET /auth/jwks.json` returns `{"keys": [{"kty": "OKP", "crv": "Ed25519", "x", "kid", "alg": "EdDSA", "use": "sig"}]}` with `Cache-Control: public, max-age=300`.
- **Login and refresh** answer `200` with the `/auth/me` body and set the three cookies (a refresh also rotates the CSRF token). Every failure is `401` with a fixed detail (`Invalid email or password.` for login, `Your session has ended. Please log in again.` for refresh and `/auth/me`) and deletes the cookies. Unknown emails still run one Argon2 check, so timing does not tell them apart. A disabled user cannot log in or refresh.
- **Lockout:** the 5th consecutive failure sets `locked_until = now + 15 min` and resets `failed_login_count`, so after the lock the user has 5 new tries. The user row is locked (`SELECT … FOR UPDATE`) during a login, so parallel attempts are all counted.
- **Refresh rotation:** the old token gets `revoked_at` and a new one (same `family_id`, 30 days from now) is stored. The row is locked during a refresh, so two refreshes with one token run in turn and the second counts as a reuse. The web app must therefore send one refresh at a time.
- **Logout** (`204`) revokes the token's whole family and deletes the cookies; an unknown or missing refresh token still gets `204`.
- **CSRF:** `POST`/`PUT`/`PATCH`/`DELETE` under `/auth/`, `/api/v1/panel/` and `/api/v1/admin/` (added in T32) need `X-CSRF-Token` equal to the `tendwise_csrf` cookie, or `403`. Exempt: `/auth/login` (no session yet) and, from T34, `/auth/invitations/accept` (it reads no cookies). The `X-Api-Key` service routes need no CSRF header (no cookies).
- **Other routes:** the service routes are left to `ApiKeyFilter` (narrowed in T32), Swagger stays public, `/auth/me` needs a valid access token (`401` otherwise), and any route not listed in `SecurityConfig` is denied.

#### Internal API (planned)

agent-service, header `X-Api-Key` with the shared service key (`BUSINESS_SERVICE_API_KEY`, already set in both services; compared in constant time). business-service calls it at `AGENT_SERVICE_URL` (T39). nginx never routes `/internal/`.

| Method | Route | Description | Planned |
|---|---|---|---|
| POST | `/internal/v1/replies` | In: `conversation_id`, `business_slug`, `message`, `staff_messages[]` (`author`, `body`, exchanged since the last AI turn). Out: `reply`, `intent`, `language`, `sources[]`, `handoff` (bool), `order_code` (or null). Same 404/503 errors as `/api/v1/chat` | T37 |
| GET | `/internal/v1/usage?from=&to=` | Per business slug and day: turns, prompt and completion tokens, `cost_usd`. Playground conversations are left out. For the nightly rollup, so business-service never reads the `agent` schema | T59 |
| POST | `/internal/v1/suggest-reply` | Drafts a reply for staff from the transcript and the knowledge base; staff edit it before sending | T72 |

- `POST /api/v1/chat` stays as the entry point for evals and local tests. From T42 nginx no longer routes it, and its in-memory rate limit only protects that internal use.
- **Deferred:** the WhatsApp webhook (T18) and the billing provider's webhook (T23) are proposed by those tasks.

### nginx routing (planned: T42)

Since T33, nginx (and the Vite dev proxy) sends `/auth/`, `/api/v1/panel/` and `/api/v1/admin/` to business-service and every other `/api/` path to agent-service, so the demo chat keeps working. Since T50, `/api/v1/panel/{slug}/documents…` goes to agent-service first (a regex location in nginx, a `^` key in Vite). From T42:

| Path | Goes to |
|---|---|
| `/api/v1/panel/{slug}/documents…` (since T50), `/api/v1/panel/{slug}/knowledge-gaps`, `/api/v1/panel/{slug}/playground` | agent-service (checked first; each task adds its own location) |
| `/public/`, `/auth/`, `/api/v1/panel/`, `/api/v1/admin/` | business-service; the SSE routes with `proxy_buffering off` and a long read timeout |
| `/api/v1/businesses`, `/api/v1/orders`, `/api/v1/handoffs`, `/internal/`, `/api/v1/chat`, agent-service's `/api/v1/admin/ingest` | Not routed: service-to-service, evals and operator tools, reachable only inside the compose network |
| Everything else | The single-page app (`index.html`) and the widget bundle |

## Security (planned)

Panel login and tokens: [ADR 0003](adr/0003-panel-authentication.md).

- **Login (T31, done):** email and password (Argon2id). After 5 consecutive failed logins the account is locked for 15 minutes (`users.failed_login_count`, `users.locked_until`); a successful login resets the count. Error messages are generic. TOTP 2FA is mandatory for platform admins and optional for owners (T68).
- **Tokens (T31, done):** the access token is a JWT signed with Ed25519, valid 15 minutes, with the user id, platform role and memberships, in an `HttpOnly; Secure; SameSite=Lax` cookie. The refresh token (30 days) is rotated on every use, and a reused one revokes the whole family. agent-service verifies the access token with `GET /auth/jwks.json`, cached (T35, done). Disabling a user revokes their refresh tokens, so access ends within 15 minutes.
- **CSRF (T31, T35):** with cookie auth, every state-changing request (`POST`, `PUT`, `PATCH`, `DELETE`) also needs an `X-CSRF-Token` header equal to the non-HttpOnly CSRF cookie (double-submit). Both services check it.
- **Tenant isolation (T32, done):** the business comes from the URL slug checked against the token's memberships, never from the body. Every repository method takes `businessId`. A shared test suite calls every panel route as a user of another business and expects 404. Optionally later: PostgreSQL row-level security as a second line of defence.
- **Support mode (T64):** a separate, read-only, 30-minute token; a banner on every page; an audit entry when it starts and for each page viewed.
- **Personal data (LGPD/GDPR):** staff see messages (they have to answer them), but logs never contain message text, phone numbers or emails. The customer's contact is stored only with consent, encrypted at rest. The retention job also purges `core.messages` and `core.conversations`, and an owner can export or delete one customer's conversations (T70). The audit log stores a hash of the IP, not the IP.
- **Public endpoints (T38):** allowed domains per business (CORS and `frame-ancestors`), rate limits, message size limits. The per-conversation limit is counted from `core.messages` in the database, so it holds across instances (this replaces agent-service's in-memory limit for customers). The per-IP limit is in memory per instance; a shared store is proposed only when more than one instance runs.
- **Merchant-provided text** (assistant tone, extra instructions, documents) goes into prompts inside delimited, length-capped sections below the system rules (T54), with eval cases for it.

## Real-time (planned: T40)

Why: [ADR 0004](adr/0004-realtime-and-jobs.md).

- **Transport: Server-Sent Events.** Messages to the server are normal `POST`s; only the server→browser direction needs push. Two streams: `GET /public/v1/conversations/{id}/events` (widget, one conversation, by token) and `GET /api/v1/panel/{slug}/events` (panel, the whole business, by session).
- **Fan-out: PostgreSQL `LISTEN/NOTIFY`.** After the commit, business-service sends `NOTIFY events, '{"businessId":…,"conversationId":…,"type":…,"id":…}'` (`id` of the message or handoff). Every instance listens on one dedicated connection and forwards the event to its open streams for that business or conversation. The payload carries ids only (the `NOTIFY` limit is 8,000 bytes); the browser fetches anything bigger.
- **Server:** Spring MVC `SseEmitter` with Java 21 virtual threads (`spring.threads.virtual.enabled=true`), so thousands of open connections do not need thousands of platform threads. A heartbeat comment every 25 seconds.
- **Missed events:** the SSE event id is the newest message id the client has. On reconnect the client sends `Last-Event-ID`: the widget stream replays that conversation's newer, non-internal messages from `core.messages` and the current state; the panel stream does not replay, and the client refetches its list. The database stays the source of truth; events are only hints.

| Event | Sent when | Widget stream | Panel stream |
|---|---|---|---|
| `message.created` | A message is stored | Yes, unless `internal` | Yes |
| `conversation.state_changed` | The state changes | Yes | Yes |
| `conversation.assigned` | The assignee changes | No | Yes |
| `conversation.read` | Staff opened the conversation | No | Yes |
| `handoff.created` | A handoff is created | No | Yes |
| `staff.typing` | The assignee is typing (`POST …/typing`) | Yes, with the first name | Yes |
| `ai.typing` | An `AI_REPLY` job starts | Yes | Yes |

`staff.typing` and `ai.typing` are sent with `NOTIFY` only and never stored.

- **As built (T40):** package `dev.ocaina.tendwise.realtime`.
  - `EventPublisher` runs `pg_notify('events', …)` inside the transaction that stores the change; PostgreSQL delivers it only after that transaction commits and drops it on rollback. Outside a transaction (`ai.typing`) it goes out at once. `ConversationService` publishes `message.created` for every stored message, `conversation.state_changed` for every transition and `conversation.assigned` on take over and release; `HandoffService` publishes `handoff.created`; `AiReplyJobHandler` publishes `ai.typing`. `conversation.read` and `staff.typing` are sent from T44.
  - The payload also has `internal` (message events, so the widget stream can skip internal messages) and `state` (the new state, on `conversation.state_changed`). Neither is personal data.
  - `NotificationListener`: one thread per instance with its own JDBC connection (outside the pool, `application_name` `tendwise-event-listener`). It reconnects after `tendwise.realtime.reconnect-delay` and then sends `resync` to every open stream, because events sent while it was disconnected are lost.
  - `EventStreams` holds the open `SseEmitter`s by business. What the browser gets: `event: <type>`, `data: {"type","conversationId","id","state"}`, and `id: <message id>` on `message.created`. Heartbeat: a `: heartbeat` comment (`tendwise.realtime.heartbeat-interval`, 25 s). A stream ends after `tendwise.realtime.stream-timeout` (30 min) and the browser reconnects. On shutdown every stream is ended, so graceful shutdown does not wait for them.
  - `resync` event: "you may have missed events, refetch". The panel stream sends it when it reconnects with `Last-Event-ID`; the widget stream instead replays up to 100 newer non-internal `message.created` events and the current state.
  - The widget stream checks the token (404) and the origin (403) like the history call. No cap on streams per conversation or business yet.
  - Spring Security lets the `ASYNC` dispatch that ends a stream through, and `PanelGuard` skips it: the request was checked when it came in.
