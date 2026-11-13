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

## Contributing

See [AGENTS.md](AGENTS.md) for the workflow: one task per branch and PR, Conventional Commits, tests for all new code.
