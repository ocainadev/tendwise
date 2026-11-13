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
- **LLM providers:** the agent depends only on the `LLMClient` port (`app/llm/client.py`); `LLM_PROVIDER` picks the adapter in `app/llm/factory.py`. Supported: `anthropic` (Messages API, key `ANTHROPIC_API_KEY`, default model `claude-haiku-5-5`) and `openai` (Responses API with `reasoning.effort=none` and `store=false`, key `OPENAI_API_KEY`, default model `gpt-6-luna`). `LLM_MODEL` overrides the default; only the selected provider's key is needed. Gemini is planned. Provider errors are caught through `app/llm/errors.py`, so callers never import an SDK.

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

#### Retrieval

- `app/rag/retriever.py`: the agent depends on the `Retriever` interface (`retrieve(business_slug, query, top_k=5) -> list[RetrievedChunk]`); `PgVectorRetriever` is the adapter. Each `RetrievedChunk` has `content`, `source_path`, `title`, `section` and `similarity`.
- **Business filter:** one SQL query, always `WHERE c.business_id = <slug>`, joined with `documents` on both `id` and `business_id`. A question for one business can never return another business's chunks.
- **Top-k:** `ORDER BY embedding <=> query LIMIT top_k` (`RAG_TOP_K`, default 5). `similarity = 1 - cosine distance`; results come back best first. `SET LOCAL hnsw.iterative_scan = relaxed_order` keeps a filtered HNSW scan from returning fewer than `top_k` rows when the index is used.
- **Threshold:** chunks with similarity below `RAG_MIN_SIMILARITY` (default **0.30**) are dropped after the query (a `WHERE` on the distance would stop PostgreSQL from using the index). An off-topic question returns `[]`, and the agent answers that it does not know instead of guessing.
- **Calibration (T08), with the real model:** 30 on-topic questions (12 per business in its language, 3 in another language) all have their best chunk at ≥ 0.347 (median ≈ 0.53). Of 16 off-topic questions, 12 have their best chunk below 0.30 (e.g. "Who won the world cup?" 0.153 / 0.073). The few above it (weather 0.37–0.41, "ignore your instructions" 0.32–0.33) only reach harmless chunks such as opening hours; the intent classifier and input guard stop those first anyway. Between 0.28 and 0.30, 13 of 14 chunks were noise. Re-check the threshold whenever the model or the content changes.

#### Migrations

Plain SQL files in `agent-service/migrations/` (`001_documents_chunks.sql`, ...), applied in file-name order by `app/core/migrations.py` on app startup and at the start of every ingestion. Applied files are recorded in `agent.schema_migrations`.

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
- **classify_intent:** the T09 classifier; also updates `negative_streak` and remembers any order code or phone digits the customer gave.
- **route:** deterministic code, checked in this order: confidence < 0.6 → handoff `LOW_CONFIDENCE`; `human_request` → `HUMAN_REQUEST`; `complaint` → `COMPLAINT`; negative sentiment on 2 consecutive turns → `NEGATIVE_SENTIMENT`; otherwise `catalog_question` → retrieve, `order_status` → ask_or_call_tool, `other` → answer_generic.
- **retrieve → answer_with_context:** top-k chunks of the business; the LLM answers only from them and the reply lists their sources. No chunks → a fixed "I don't have that information, want a person?" reply, without an LLM call.
- **ask_or_call_tool:** asks (fixed text) for whatever is missing of order code and last 4 phone digits; with both, calls `get_order_status` through `BusinessGateway`. Not found → a fixed "could not find an order with that code and phone" reply; found → the LLM phrases the status from the tool data.
- **create_handoff:** the LLM writes a staff-facing summary (max 2,000 characters, in the business's default language), the gateway calls `POST /handoffs`, and the customer gets a fixed "a person will continue" reply.
- **answer_generic:** greetings and anything else; stays in the business's scope and declines off-topic requests.
- **remember_turn:** appends the message and the final reply to `history` (last 40 messages). Blocked messages are not remembered.
- Replies are in the classified language (`en`, `es`, `pt`). Prompts live in `app/prompts/*.md`; fixed replies in `app/agent/replies.py`. Tool calls are recorded in the state with the phone masked (`****1234`).

### Memory

- State that **persists across turns** of a conversation: `history`, `negative_streak`, `pending_order_code`, `pending_phone_last4` (the last two are cleared once `get_order_status` has been called). Every other field (`intent`, `chunks`, `reply`, `sources`, `handoff`, `blocked`, `guard_reason`, `tool_calls`, `llm_usage`, ...) is reset at the start of each turn.
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
- A blocked message gets a fixed refusal from `app/guardrails/replies.py` ("please send a shorter message", "I can only help with questions about <business>", "let's keep it respectful"). It never reached the classifier, so the reply language is guessed by `language.py` from common en/es/pt words, falling back to the business's `default_language`.
- `guard_reason` is stored in the state (per turn) for the turn log; only the rule name is ever logged, never the message.

**guard_output** (`app/guardrails/output.py`, runs on every reply, blocked ones too):

- Phone numbers (7 or more digits, with spaces, dots, dashes, parentheses or a leading `+`) become `****` + the last 4 digits (`****0102`), the same `mask_phone_last4` format used for stored tool calls. Prices (`$14.00`, `R$ 199,90`), times (`11:00–22:00`), ISO dates, order codes (`JP-1042`) and short numbers are left alone.
- E-mail addresses become `****@domain`.
- Replies over 1,200 characters are cut at the last full sentence and end with " …".
- This masks the business's own phone and e-mail too, if the model copies them from a document: the spec puts no phone number in a reply, and a person can share contacts after a handoff.
- Customer names never reach the agent: business-service's order status response has no customer data (`code`, `status`, `fulfilment`, `eta`, `updated_at`), and a test fails if a field is added.

### BusinessGateway

`app/gateways/business_gateway.py`: the `BusinessGateway` interface (`get_business`, `get_order_status`, `create_handoff`) and `HttpBusinessGateway`, which calls business-service with `X-Api-Key` and a 5-second timeout, maps its camelCase JSON to snake_case models and returns `None` on 404. httpx request logging is limited to warnings, because the order status URL carries `phoneLast4`.

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
| POST | `/api/v1/chat` | In: `conversation_id` (optional UUID; absent or `null` starts a conversation), `business_slug`, `message` (max 4,000 chars; an empty message or one over 1,000 gets guard_input's polite reply with 200). Out: `conversation_id`, `reply`, `intent` (`null` when guard_input blocked the message), `language`, `sources[]` (`title`, `source_path`, `section`), `handoff` (bool). 404 unknown business, 409 conversation of another business, 422 invalid body, 429 rate limit, 503 business-service or LLM unavailable |
| GET | `/health` | Service and database health |
| POST | `/api/v1/admin/ingest` | Re-index `data/businesses` (admin key required) |

- `POST /api/v1/chat` errors are JSON `{"detail": "..."}` and never contain exception text: `404` `Business not found`; `409` `Conversation belongs to another business` (tenant isolation: the business slug stored in the conversation's checkpoint must match the request); `429` when a conversation sends more than `CHAT_RATE_LIMIT_MESSAGES` (20) messages in `CHAT_RATE_LIMIT_WINDOW_SECONDS` (600) — an in-memory sliding window per process, to move to a shared store such as Redis when there is more than one process; `503` `Business service unavailable` (unreachable, 5xx, or `BUSINESS_SERVICE_API_KEY` unset) or `Assistant temporarily unavailable` (LLM provider error, the selected provider's key — `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` — unset, or the agent could not be built at startup). The agent service is built once at startup; if that fails, the app still starts and `/health` still works.

- `POST /api/v1/admin/ingest` requires the `X-Admin-Key` header (compared in constant time with `ADMIN_API_KEY`): 401 if it is missing or wrong, 503 if `ADMIN_API_KEY` is not configured. 200 body: `{"added", "updated", "skipped", "deleted"}` (document counts).
