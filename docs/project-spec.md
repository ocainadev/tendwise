# Tendwise – Technical spec and delivery plan

Nov 12, 2026 · @Caina · Status: ready to start

> For whoever is guiding the coding agents: this document is the project's source of truth. Every backlog task (section 11) becomes one branch and one pull request. Agents must follow the rules in section 13 (AGENTS.md). Cainã reviews and approves every PR and must understand every line, because he will explain this project in technical interviews.

## 1. Overview

**Problem:** small businesses — shops, restaurants, salons, pharmacies, local stores — lose sales because they reply slowly to the same questions all day (prices, stock, opening hours, delivery fees) and to "where is my order?".

**Product:** Tendwise is an AI clerk that any small business can plug into its customer chat. It:

- answers questions with RAG over the business's own catalog, FAQ and policies;
- detects the customer's intent (catalog question, order status, complaint, human request, other);
- checks order status through a real API using tool calling;
- hands off to a human when the case is complex, the customer is upset or asks for a person;
- replies in the customer's language (English, Spanish, Portuguese);
- logs tokens, latency and cost per reply and is measured against an evaluation dataset.

**Goals:**

1. Portfolio project for the AI Engineer track of iFood's GEN IA program, with the MVP published by Nov 17, 2026.
2. Foundation for a sellable international product: multi-tenant (many businesses, one system) from day one; WhatsApp channel right after the MVP.

**MVP scope:** web chat, two demo businesses (a pizzeria and a clothing store), RAG, order status lookup, human handoff, guardrails, observability, evals, docker compose, README with a demo GIF.

**Out of MVP:** WhatsApp, public deploy, merchant login, catalog upload from PDF, billing.

## 2. Positioning

- **Who it is for:** brick-and-mortar and local small businesses that sell through chat, not big e-commerce stores (those are already served by Shopify-native tools).
- **Where:** WhatsApp-first markets — Latin America, Southern Europe, India, Southeast Asia — where customers already message businesses every day. Start in Brazil to get real users and testimonials, then expand to Spanish- and English-speaking markets with the same product.
- **Why it wins:** simple flat monthly pricing (no per-resolution surprises), multilingual by default, set up in minutes from a catalog and an FAQ, and a clear human handoff.

## 3. Architecture

Two services with separate responsibilities, in one monorepo:

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

**Why this architecture:**

- **Domain is separated from AI.** The agent never touches the orders database directly; it can only do what the tools expose through the API. This is a security decision (the LLM never runs arbitrary SQL) and it is how production systems are built.
- **Plays to Cainã's strength.** business-service in Spring Boot shows the Java backend he already knows; agent-service in Python shows the AI stack iFood uses.
- **LangGraph instead of an ad-hoc loop.** The agent is an explicit state graph (classify → route → act → validate), easy to test node by node and easy to explain in an interview.
- **pgvector instead of a separate vector database.** One database, no extra cost, and Cainã already knows PostgreSQL.
- **Multi-tenant from the start.** Every table has `business_id`, so one deployment can serve many paying businesses later.
- **Any kind of business.** The domain is generic: a business has a catalog (products or services), orders with a fulfilment type (delivery, pickup, in-store) and policies. Business-specific knowledge lives in documents indexed by RAG, not in code.

**Accepted trade-off:** two services are more complex than one. Docker compose makes it one command to run everything. Record this in `docs/adr/0001-two-service-architecture.md`.

**Internal structure:**

- **business-service:** package by feature (business, catalog, order, handoff), each layered controller → service → repository, with DTOs separate from entities.
- **agent-service:** light ports & adapters. The agent graph depends on interfaces (`LLMClient`, `Retriever`, `BusinessGateway`); concrete implementations live in adapters. This lets us swap LLM providers and mock everything in tests.

## 4. Stack

| Layer | Technology | Why |
|---|---|---|
| Domain service | Java 21, Spring Boot 3, Maven, Spring Data JPA, Flyway | Cainã's strength; industry standard |
| AI service | Python 3.12, FastAPI, Pydantic v2, LangGraph, uv | Most used AI stack; typed with Pydantic |
| LLM | Provider set in `.env` (OpenAI, Anthropic or Gemini) | No vendor lock-in; compare models in evals |
| Embeddings + search | pgvector (HNSW index) | One database |
| Database | PostgreSQL 16 | Already known |
| Frontend | React + Vite + TypeScript | Simple chat, fast to build |
| Tests | JUnit 5 + Testcontainers; pytest; Vitest | Real database in tests for both services |
| Code quality | Ruff (Python), Spotless (Java), ESLint (web) | Automatic lint in CI |
| Infra | Docker Compose; GitHub Actions | One command to run; CI on every PR |

## 5. Repository structure

Repository: `github.com/ocainadev/tendwise` (monorepo). All code, comments, commits and docs in English.

```
tendwise/
├── AGENTS.md                     # rules for coding agents (section 13)
├── README.md                     # pitch, architecture, how to run, GIF, eval results
├── docker-compose.yml
├── .env.example                  # every variable, no secret values
├── .github/
│   ├── workflows/ci.yml
│   └── pull_request_template.md
├── docs/
│   ├── architecture.md           # API contracts and data model (sections 7 and 8)
│   └── adr/0001-two-service-architecture.md
├── data/
│   └── businesses/
│       ├── joes-pizza/           # catalog.md, faq.md, policies.md
│       └── denim-corner/         # clothing store
├── business-service/
│   ├── pom.xml
│   └── src/
│       ├── main/java/dev/ocaina/tendwise/
│       │   ├── business/         # controller, service, repository, dto, entity
│       │   ├── catalog/
│       │   ├── order/
│       │   ├── handoff/
│       │   └── shared/           # config, API-key security, error handler
│       ├── main/resources/db/migration/   # V1__schema.sql, V2__seed.sql
│       └── test/java/...
├── agent-service/
│   ├── pyproject.toml
│   ├── app/
│   │   ├── main.py
│   │   ├── api/                  # routes: chat, health, admin/ingest
│   │   ├── agent/                # state.py, graph.py, nodes/
│   │   ├── prompts/              # versioned prompts as .md
│   │   ├── tools/                # get_order_status, create_handoff
│   │   ├── rag/                  # chunking, embeddings, retriever
│   │   ├── guardrails/           # input and output validation
│   │   ├── observability/        # turn logging, tokens, cost
│   │   ├── llm/                  # LLMClient interface + provider adapters
│   │   ├── gateways/             # BusinessGateway (HTTP client for business-service)
│   │   └── core/                 # config, logging, db
│   ├── scripts/ingest.py
│   ├── evals/
│   │   ├── dataset.jsonl
│   │   └── run_evals.py
│   └── tests/
└── web/
    ├── package.json
    └── src/                      # components/Chat, api/client.ts
```

## 6. Agent flow (LangGraph)

```
message → guard_input → classify_intent → route ─┬─ catalog_question → retrieve → answer_with_context ─┐
                                                 ├─ order_status     → ask_or_call_tool → answer ──────┤
                                                 ├─ complaint / human_request / low confidence          │
                                                 │                   → create_handoff → answer ────────┤
                                                 └─ other            → answer_generic ─────────────────┤
                                                                                                       ▼
                                                                          guard_output → log_turn → reply
```

**Nodes:**

- **guard_input:** caps message length (1,000 characters), detects obvious prompt injection and abusive messages.
- **classify_intent:** LLM call with structured output (Pydantic): `intent`, `confidence` (0–1), `sentiment` (positive/neutral/negative), `language` (en/es/pt), `order_code` (if the customer mentioned one).
- **route:** deterministic rules, not the LLM. Goes to handoff if intent is `complaint` or `human_request`, if sentiment is negative twice in a row, or if confidence < 0.6.
- **retrieve:** top-5 chunks for the right business (filter by `business_id`) with a minimum similarity threshold.
- **answer_with_context:** answers only from the chunks, in the customer's language, and returns the sources used. If the answer is not in the chunks, it says so and offers a human.
- **ask_or_call_tool:** if the order code or the last 4 phone digits are missing, asks the customer. With both, calls `get_order_status`.
- **create_handoff:** calls the tool, which creates a handoff in business-service with an LLM-written summary of the conversation.
- **guard_output:** makes sure no phone number or other customer's data appears in the reply, and caps length.
- **log_turn:** stores the turn with intent, tool calls, tokens, latency and estimated cost.

**Memory:** conversation state persisted per `conversation_id` with LangGraph's PostgreSQL checkpointer.

## 7. Data model

**Schema `core`** (owned by business-service, Flyway migrations):

| Table | Main columns |
|---|---|
| `businesses` | id, slug (unique), name, category, default_language, timezone, opening_hours (jsonb), created_at |
| `catalog_items` | id, business_id, name, description, price, currency, available, kind (product/service) |
| `orders` | id, code (unique, e.g. JP-1042), business_id, customer_name, customer_phone, fulfilment (delivery/pickup/in_store), status, eta, created_at, updated_at |
| `handoffs` | id, business_id, conversation_id, reason, summary, status, created_at, updated_at |

- `orders.status`: RECEIVED, IN_PROGRESS, READY, OUT_FOR_DELIVERY, COMPLETED, CANCELLED.
- `handoffs.status`: OPEN, IN_PROGRESS, RESOLVED.

**Schema `agent`** (owned by agent-service):

| Table | Main columns |
|---|---|
| `documents` | id, business_id, source_path, title, content_hash, updated_at |
| `chunks` | id, document_id, business_id, content, embedding vector(N), metadata (jsonb) |
| `conversations` | id, business_id, channel (web/whatsapp), language, status, created_at |
| `turns` | id, conversation_id, role, content, intent, tool_calls (jsonb), prompt_tokens, completion_tokens, latency_ms, cost_usd, model, created_at |

- N depends on the embedding model; set in config and in the migration.
- `content_hash` makes ingestion idempotent: unchanged documents are not reprocessed.

## 8. API contracts

**business-service** (prefix `/api/v1`; every call requires the `X-Api-Key` header):

| Method | Route | Description |
|---|---|---|
| GET | `/businesses/{slug}` | Business info (hours, category, language) |
| GET | `/businesses/{slug}/catalog` | Catalog items |
| GET | `/orders/{code}/status?phoneLast4=1234` | Status and ETA. Returns 404 if the code does not exist or the phone does not match, without telling the two apart |
| POST | `/handoffs` | Create handoff: businessSlug, conversationId, reason, summary |
| GET | `/handoffs?status=OPEN` | List handoffs by status |
| PATCH | `/handoffs/{id}` | Update status |

Errors follow RFC 7807 (Problem Details); docs via springdoc/Swagger at `/swagger-ui`.

**agent-service:**

| Method | Route | Description |
|---|---|---|
| POST | `/api/v1/chat` | In: conversation_id (optional), business_slug, message. Out: conversation_id, reply, intent, language, sources[], handoff (bool) |
| GET | `/health` | Service and database health |
| POST | `/api/v1/admin/ingest` | Re-index data/businesses (admin key required) |

## 9. Security and guardrails

- **Least-privilege tools:** only `get_order_status` (read) and `create_handoff`. No tool changes orders.
- **Customer verification:** order status only with order code + last 4 phone digits.
- **Tenant isolation:** every search and every tool filters by `business_id`.
- **Retrieved content is data, not instructions:** stated in the system prompt; chunks are delimited.
- **Assistant scope:** answers only about the business and politely declines anything else.
- **Personal data:** phone numbers masked in logs (`****1234`); never log the full number. Design with GDPR and LGPD in mind (data minimisation, retention limit on conversations).
- **Secrets:** only in `.env` (git-ignored); `.env.example` lists variables without values.
- **Cost control:** `max_tokens` per reply and a per-conversation rate limit.

## 10. Observability and evaluation

**Observability:** every turn stored in `agent.turns`. A simple script or endpoint reports latency p50/p95, average cost per conversation, intent distribution and handoff rate.

**Evaluation** (the differentiator for an AI Engineer):

- `evals/dataset.jsonl` with 45 cases. Each line has `input`, `business`, `language`, `expected_intent`, `expected_tool` (or null), `must_contain` and `must_not_contain`.
- Coverage: catalog questions (15), order status (10), complaints and human requests (8), prompt injection and off-topic (7), and the same questions in English, Spanish and Portuguese (5).
- `run_evals.py` measures intent accuracy, tool-selection accuracy, groundedness, reply-language accuracy, average latency and cost.
- MVP targets: intent ≥ 90%, tool ≥ 95%, reply language 100%, 100% of injection attempts blocked.
- Results as a table in the README, ideally comparing two models.

## 11. Task backlog

One task = one branch = one PR. "Depends on" lists what must already be on main.

**MVP (by Nov 17):**

| ID | Branch | Task | Depends on | Acceptance criteria |
|---|---|---|---|---|
| T01 | `chore/T01-monorepo-setup` | Folder structure, initial README, .gitignore, .env.example, docker-compose with Postgres + pgvector, AGENTS.md, PR template | – | `docker compose up db` starts and `CREATE EXTENSION vector` works |
| T02 | `ci/T02-github-actions` | CI running lint and tests for all three projects on every PR | T01 | PR shows green checks |
| T03 | `feat/T03-business-service-domain` | Spring Boot scaffold, entities, Flyway migrations, seed with 2 businesses, 30 catalog items, 20 orders | T01 | Starts via compose; repository test with Testcontainers passes |
| T04 | `feat/T04-order-status-endpoint` | Order status endpoint with phone check and API-key auth | T03 | Tests cover success, unknown code and wrong phone (same 404) |
| T05 | `feat/T05-handoff-endpoints` | Handoff CRUD | T03 | Tests for create, list by status and update |
| T06 | `feat/T06-agent-service-scaffold` | FastAPI, Pydantic Settings config, /health, LLMClient interface + 1 adapter, Dockerfile | T01 | /health returns 200 via compose |
| T07 | `feat/T07-rag-ingestion` | Chunk the .md files, embed, store in pgvector, ingest script | T06 | Running it twice creates no duplicate chunks |
| T08 | `feat/T08-rag-retriever` | Top-k search with business filter and similarity threshold | T07 | Test: a pizzeria question never returns clothing-store chunks |
| T09 | `feat/T09-intent-classifier` | Classification node with structured output, including language | T06 | Tests with mocked LLM; invalid output handled |
| T10 | `feat/T10-agent-graph` | LangGraph wiring, routing, tools via BusinessGateway, per-conversation memory | T04, T05, T08, T09 | Tests for every route with mocked LLM |
| T11 | `feat/T11-guardrails` | guard_input and guard_output | T10 | Tests for injection, length and phone masking |
| T12 | `feat/T12-observability` | Turn logging with tokens, latency and cost; summary script | T10 | Every /chat call writes one row to turns |
| T13 | `feat/T13-chat-api` | POST /api/v1/chat wired to the graph | T10 | End-to-end test with mocked LLM |
| T14 | `feat/T14-web-chat` | React chat: business picker, messages, sources and handoff notice | T13 | Full conversation in the browser |
| T15 | `test/T15-evals` | 45-case dataset and metrics runner | T13 | Report generated; section 10 targets met or documented |
| T16 | `docs/T16-readme-demo` | Final README: problem, architecture, how to run, GIF, eval table, decisions; tag v0.1.0 | T14, T15 | Someone new can run the project from the README alone |

**After the MVP:**

| ID | Branch | Task |
|---|---|---|
| T17 | `feat/T17-handoff-panel` | Web panel for staff to see and take over handoffs |
| T18 | `feat/T18-whatsapp-channel` | WhatsApp channel (WhatsApp Business Cloud API) |
| T19 | `feat/T19-catalog-upload` | Merchant uploads a catalog as PDF/photo and it gets indexed |
| T20 | `ci/T20-deploy` | Public deploy with a demo URL |
| T21 | `feat/T21-tracing` | Tracing with Langfuse or OpenTelemetry |
| T22 | `feat/T22-merchant-auth` | Merchant login and per-business API keys |
| T23 | `feat/T23-billing` | Subscription billing (Stripe) |

## 12. Git workflow

**Branches:**

- `main` is protected: code only lands through a PR with green CI and Cainã's approval.
- One short-lived branch per task, created from an up-to-date main and deleted after merge.
- Name: `<type>/<ID>-<short-kebab-case-description>`. Examples: `feat/T07-rag-ingestion`, `fix/T24-duplicate-handoff`, `docs/T16-readme-demo`.
- Branch types match commit types: feat, fix, docs, test, refactor, chore, ci, perf.

**Commits (Conventional Commits):**

```
<type>(<scope>): <short imperative description, max 72 chars>

<optional body: why the change was made, not how>

Refs: T07
```

- Scopes: business, agent, rag, web, evals, infra, docs.
- Examples:
  - `feat(business): add order status endpoint with phone check`
  - `feat(rag): store chunk embeddings in pgvector`
  - `fix(agent): route to handoff when confidence is low`
  - `test(evals): add prompt injection cases`
  - `chore(infra): add pgvector to docker compose`
- Small, atomic commits: each one builds and passes tests.
- Breaking contract change: `feat(business)!: ...` with `BREAKING CHANGE:` in the footer.

**Pull requests:**

- Title in commit format with the ID: `feat(rag): ingestion pipeline (T07)`.
- Small: ideally under 400 changed lines. If larger, split the task.
- Squash merge, so main has one clean commit per task.
- Template (`.github/pull_request_template.md`):

```markdown
## Task
T07 – <name>

## What changed
- ...

## How to test
1. ...

## Checklist
- [ ] Task acceptance criteria met
- [ ] Tests added and passing
- [ ] Lint clean
- [ ] No secrets or .env in the diff
- [ ] README/docs updated if needed
```

**Versions:** tag `v0.1.0` when the MVP closes (T16); SemVer after that.

## 13. Instructions for coding agents (AGENTS.md)

Put this at the repository root as `AGENTS.md` (and copy it to `CLAUDE.md` if the agent you use reads that name):

```markdown
# AGENTS.md

## Context
Tendwise: an AI customer-service clerk for any small business.
Source of truth: docs/architecture.md and the task backlog.
Monorepo: business-service (Java 21/Spring Boot), agent-service (Python 3.12/FastAPI/LangGraph), web (React/Vite/TS).
Everything in English: code, comments, commits, PRs, docs.

## Workflow
1. Work on ONE task at a time, identified by its ID (e.g. T07).
2. Branch from an up-to-date main:
   git switch main && git pull && git switch -c feat/T07-rag-ingestion
3. Read the acceptance criteria before writing code. If anything is ambiguous, stop and ask.
4. Make small Conventional Commits with the footer "Refs: T07".
5. Run lint and tests before every push.
6. Open the PR using the template. Never merge yourself: merging is done by a human.

## Rules
- Never commit secrets, .env files or API keys.
- Do not change files outside the task's scope.
- Do not create endpoints, tables or fields that are not in docs/architecture.md. If you need one, propose it in the PR.
- Do not add dependencies without justifying them in the PR.
- All new code has tests. In unit tests the LLM and business-service are always mocked.
- Prompts live in agent-service/app/prompts/*.md, never inline in code.
- Every data query filters by business_id.
- Never log full phone numbers or personal data.
- Prefer simple, readable code over abstractions: a junior developer must be able to understand and explain every part.

## Commands
- Everything: docker compose up --build
- business-service: cd business-service && ./mvnw verify
- agent-service: cd agent-service && uv run ruff check . && uv run pytest
- web: cd web && npm run lint && npm test
- Ingestion: cd agent-service && uv run python scripts/ingest.py
- Evals: cd agent-service && uv run python evals/run_evals.py
```

## 14. Timeline (5 days)

Two parallel tracks: A (Java, business-service) and B (Python, agent-service). Suggestion: Cainã drives track A, where he is already strong, and reviews track B closely, where he is learning.

| Day | Track A | Track B | End-of-day result |
|---|---|---|---|
| Fri Nov 13 | T01, T02, T03 | T06 | Infra and both services running via compose |
| Sat Nov 14 | T04, T05 | T07, T08 | Orders API done; RAG working |
| Sun Nov 15 | Review and tests | T09, T10, T13 | Agent answering through the API |
| Mon Nov 16 | T14 (web) | T11, T12, T15 | Browser chat, guardrails and evals |
| Tue Nov 17 (morning) | T16 | T16 | README, GIF, tag v0.1.0, link on the résumé |

**Important:** the GEN IA application should not wait for the project. Apply first, then update the résumé and LinkedIn once the MVP is published. If time runs short, cut in this order: T12, T11, T14 (demo through Swagger). Never cut T15 and T16.

## 15. Definition of Done

A task is done only when:

- its acceptance criteria are met;
- tests are written and passing, and CI is green;
- there are no secrets in the diff;
- docs are updated if a contract or command changed;
- Cainã has reviewed, understood and approved the PR;
- it was squash-merged and the branch was deleted.
