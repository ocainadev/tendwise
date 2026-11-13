# Tendwise

An AI clerk that any small business can plug into its customer chat.

Small businesses (shops, restaurants, salons, pharmacies) lose sales because they answer the same questions all day: prices, stock, opening hours, delivery fees, "where is my order?". Tendwise answers them for the business:

- answers from the business's own catalog, FAQ and policies (RAG);
- detects the customer's intent and replies in their language (English, Spanish, Portuguese);
- checks order status through a real API (tool calling);
- hands off to a human when the customer is upset, asks for a person or the case is complex;
- logs tokens, latency and cost per reply, and is measured against an evaluation dataset.

> Status: work in progress. The MVP (web chat with two demo businesses) is being built.

## Architecture

| Part | Stack | Responsibility |
|---|---|---|
| `business-service/` | Java 21, Spring Boot 3 | Businesses, catalog, orders, handoffs |
| `agent-service/` | Python 3.12, FastAPI, LangGraph | Agent graph, RAG, guardrails, evals |
| `web/` | React, Vite, TypeScript | Chat UI |
| `db` | PostgreSQL 16 + pgvector | One database, `core` and `agent` schemas |

Details: [docs/architecture.md](docs/architecture.md) · Decision record: [docs/adr/0001-two-service-architecture.md](docs/adr/0001-two-service-architecture.md) · Full plan: [docs/project-spec.md](docs/project-spec.md)

## Run locally

Requirements: Docker with Docker Compose.

```bash
cp .env.example .env   # then fill in the values (at least POSTGRES_PASSWORD)
docker compose up db
```

Check that pgvector is available:

```bash
docker compose exec db psql -U tendwise -d tendwise -c "SELECT extversion FROM pg_extension WHERE extname = 'vector';"
```

Start business-service (applies the Flyway migrations and the demo seed on first start, port 8080):

```bash
docker compose up --build business-service
```

Look up a demo order (`BUSINESS_SERVICE_API_KEY` from your `.env`). API docs: http://localhost:8080/swagger-ui

```bash
curl -H "X-Api-Key: $BUSINESS_SERVICE_API_KEY" "http://localhost:8080/api/v1/orders/JP-1042/status?businessSlug=joes-pizza&phoneLast4=0102"
```

Run its tests (needs Docker for Testcontainers):

```bash
cd business-service && ./mvnw verify
```

### agent-service

Start it with the database (port 8000). It starts without an LLM key; only LLM calls need `ANTHROPIC_API_KEY`.

```bash
docker compose up --build agent-service
```

Check its health (`503` with `"database": "error"` if PostgreSQL is unreachable):

```bash
curl http://localhost:8000/health
```

Index the business content (`data/businesses/<slug>/*.md`) into pgvector. Safe to run again: unchanged files are skipped, so no duplicate chunks. The first run downloads the embedding model (about 220 MB, into the `embedding-models` volume; locally into `agent-service/.cache/fastembed`):

```bash
docker compose exec agent-service python scripts/ingest.py
```

Or over HTTP, with `ADMIN_API_KEY` from your `.env` (401 with a wrong key, 503 if no key is configured):

```bash
curl -X POST -H "X-Admin-Key: $ADMIN_API_KEY" http://localhost:8000/api/v1/admin/ingest
```

Outside Docker, from `agent-service/` with `DATABASE_URL` set: `uv run python scripts/ingest.py`.

#### Chat

With business-service running, the content ingested and `ANTHROPIC_API_KEY` set in `.env`, send a message. Leave out `conversation_id` (or send `null`) to start a conversation; send back the returned `conversation_id` to continue it:

```bash
curl -X POST http://localhost:8000/api/v1/chat -H "Content-Type: application/json" -d '{"business_slug": "joes-pizza", "message": "How much is delivery?"}'
```

The reply has `conversation_id`, `reply`, `intent` (`null` if the guardrails blocked the message, which is still a normal `200` reply), `language`, `sources` and `handoff`. Errors: `404` unknown business, `409` conversation of another business, `422` invalid body (e.g. a message over 4,000 characters), `429` too many messages in one conversation (`CHAT_RATE_LIMIT_MESSAGES` per `CHAT_RATE_LIMIT_WINDOW_SECONDS`, 20 per 10 minutes by default), `503` business-service or the LLM unavailable (also when `ANTHROPIC_API_KEY` is not set). To chat in the browser, start the web app (below) and open http://localhost:5173.

Run its lint and tests (needs [uv](https://docs.astral.sh/uv/) and Docker: ingestion tests start a pgvector container with Testcontainers; the LLM and the embedding model are mocked):

```bash
cd agent-service && uv run ruff check . && uv run ruff format --check . && uv run pytest
```

#### Evals

`agent-service/evals/dataset.jsonl` holds 45 customer messages (catalog, order status, complaints and human requests, prompt injection and off-topic, and the same questions in en/es/pt), each with the expected intent, tool and facts. `evals/run_evals.py` sends every case, as a new conversation, through the real agent (real LLM, retrieval, business-service and Postgres) and measures intent accuracy, tool-selection accuracy, groundedness, reply-language accuracy, injection-blocked rate, average latency and cost. It needs the stack running (`docker compose up`), the content ingested and `ANTHROPIC_API_KEY` set. From `agent-service/`, point it at the database and business-service on localhost and read the rest from the root `.env`:

```bash
DATABASE_URL=postgresql://tendwise:<password>@localhost:5432/tendwise BUSINESS_SERVICE_URL=http://localhost:8080 uv run python evals/run_evals.py --env-file ../.env
```

Add `--model claude-sonnet-5-5` to evaluate another model, and `--limit 5` for a quick smoke run. Each run writes `evals/results/<model>-<date>.md` (metrics vs the MVP targets, plus every failed case) and prints a summary. It exits non-zero only when it cannot run (for example a missing key), not when a target is missed. The run writes its turns and handoffs to the database like real customers would, so use a development database. The first run outside Docker downloads the embedding model (about 220 MB) into `agent-service/.cache/fastembed`.

### web

The chat UI talks to agent-service through a relative `/api` URL (Vite's dev proxy or nginx forwards it, so no CORS). Pick a demo business (Joe's Pizza or Denim Corner) and ask away.

With agent-service running on port 8000 (needs Node 24):

```bash
cd web && npm install && npm run dev
```

Or everything in Docker (nginx serves the built app and proxies `/api` to agent-service):

```bash
docker compose up --build web
```

Either way, open http://localhost:5173.

Run its lint and tests (`fetch` is mocked, no backend needed):

```bash
cd web && npm run lint && npm test
```

## Contributing

See [AGENTS.md](AGENTS.md) for the workflow: one task per branch and PR, Conventional Commits, tests for all new code.
