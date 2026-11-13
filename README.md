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

Run its lint and tests (needs [uv](https://docs.astral.sh/uv/) and Docker: ingestion tests start a pgvector container with Testcontainers; the LLM and the embedding model are mocked):

```bash
cd agent-service && uv run ruff check . && uv run ruff format --check . && uv run pytest
```

## Contributing

See [AGENTS.md](AGENTS.md) for the workflow: one task per branch and PR, Conventional Commits, tests for all new code.
