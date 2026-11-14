# ADR 0004: SSE, PostgreSQL LISTEN/NOTIFY and a job table

- Status: accepted
- Date: 2026-11-13
- Plan: [panels-plan.md](../panels-plan.md), sections 3.2, 7 and 13 (decision 3)

## Context

With conversations in business-service ([ADR 0002](0002-conversations-in-business-service.md)), an answer can come from the AI seconds later or from a person minutes later. The customer's chat and the staff inbox both need to see new messages without reloading. A WhatsApp webhook (T18) must answer within seconds, so a slow LLM call cannot sit inside the request anyway. business-service may run as more than one instance, so an event created on one instance must reach browsers connected to another.

## Decision

1. **AI replies are asynchronous.** `POST /public/v1/conversations/{id}/messages` stores the customer message and inserts an `AI_REPLY` job in the same transaction, then answers `202`.
2. **Jobs live in a PostgreSQL table (`core.jobs`).** A worker takes due jobs with `SELECT … FOR UPDATE SKIP LOCKED`, so several instances never take the same job. Failures retry with backoff (3 attempts); the final failure of an `AI_REPLY` stores a `SYSTEM` message and moves the conversation to `WAITING`, so a person picks it up.
3. **Server push uses Server-Sent Events (SSE).** The browser sends with normal `POST`s; only the server→browser direction needs push. Spring MVC `SseEmitter` on Java 21 virtual threads, a heartbeat every 25 seconds, resume with `Last-Event-ID`.
4. **Fan-out uses PostgreSQL `LISTEN/NOTIFY`.** After each commit business-service sends `NOTIFY events` with ids only. Every instance listens on one dedicated connection and forwards the event to its open SSE streams for that business (panel) or conversation (widget). The database stays the source of truth; events are hints, and a client that missed too many refetches.

## Alternatives considered

- **Keep AI replies synchronous and use SSE only for staff messages.** Less work now, but WhatsApp needs asynchronous replies, so the AI path would be rebuilt later, and the widget would handle two different reply paths.
- **WebSockets.** Two-way, but we only need server→browser; SSE is plain HTTP, reconnects by itself and passes nginx with `proxy_buffering off`.
- **Redis pub/sub or a message broker (RabbitMQ, Kafka) for jobs and fan-out.** Built for higher load, but one more piece of infrastructure to run, secure and explain. PostgreSQL is already there. We switch only if the load needs it.
- **In-process `@Async` tasks.** Simple, but jobs are lost on restart and cannot be shared between instances.

## Consequences

Good:

- No new infrastructure: the job queue and the event bus are PostgreSQL features.
- Jobs survive restarts; inserting the job in the same transaction as the message means a stored message always gets an answer attempt.
- Works with several business-service instances.
- Easy to explain: a table, a `SELECT … SKIP LOCKED`, a `NOTIFY` and an HTTP stream.

Bad (accepted trade-off):

- Each instance holds one extra database connection for `LISTEN`, and one open HTTP connection per browser tab (cheap with virtual threads).
- `NOTIFY` payloads are capped (8,000 bytes), so they carry ids only and the browser fetches the rest.
- A PostgreSQL queue has lower throughput than a broker. Fine for the first clients; revisit when jobs pile up.
- The widget runs in an iframe where third-party cookies are blocked, so it reads the stream with `fetch` and an `Authorization` header instead of `EventSource` (about 40 lines, no dependency).
