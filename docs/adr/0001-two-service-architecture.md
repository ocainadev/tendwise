# ADR 0001: Two services in one monorepo

- Status: accepted
- Date: 2026-11-12

## Context

Tendwise needs a domain backend (businesses, catalog, orders, handoffs) and an AI agent (RAG, intent classification, tool calling, guardrails, evals). The agent must be able to look up order status and create handoffs, but it must not be able to change orders or run arbitrary queries against business data.

## Decision

Split the system into two services in one monorepo, sharing one PostgreSQL database with separate schemas:

- **business-service** (Java 21, Spring Boot 3) owns the `core` schema and exposes a small REST API protected by an API key.
- **agent-service** (Python 3.12, FastAPI, LangGraph) owns the `agent` schema (documents, chunks, conversations, turns). It reaches business data only through business-service, via two tools: `get_order_status` (read) and `create_handoff`.

## Consequences

Good:

- The LLM can only do what the API exposes. It never writes SQL against orders and never changes them (least privilege).
- Each service uses the best-fit ecosystem: Spring Boot for the transactional domain, Python for the AI stack.
- Each side can be tested in isolation: business-service with Testcontainers, agent-service with a mocked `BusinessGateway`.

Bad (accepted trade-off):

- Two services are more complex than one: two build systems, an HTTP contract to keep in sync, and an extra network hop per tool call.
- Mitigation: Docker Compose runs everything with one command, the contract lives in `docs/architecture.md`, and CI checks both services on every PR.
