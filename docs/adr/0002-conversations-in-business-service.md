# ADR 0002: Conversations and messages live in business-service

- Status: accepted
- Date: 2026-11-13
- Plan: [panels-plan.md](../panels-plan.md), sections 3 and 13 (decision 1)

## Context

In v0.1.0 the customer talks to agent-service (`POST /api/v1/chat`), which answers in the same HTTP request. Only the AI's replies are stored (`agent.turns`); the customer's messages exist only inside the LangGraph checkpoints. A handoff is a row in `core.handoffs` that nobody can see, and the AI keeps answering after it.

The business panel needs staff to see every conversation live, take one over, reply, hand it back to the AI and resolve it. A human reply does not fit the v0.1.0 model: it arrives minutes later, from someone else, and has to be pushed to the customer. Inbox, assignment, users, roles and plans are business rules, not AI.

## Decision

business-service owns conversations and messages (`core.conversations`, `core.messages`) and routes every new customer message. agent-service becomes the AI responder behind an internal API.

- One field, `core.conversations.state`, decides who answers: `AI` (the agent), `WAITING` (nobody yet; staff are notified), `HUMAN` (only the assignee), `RESOLVED`.
- In state `AI`, business-service stores the customer message and enqueues an `AI_REPLY` job. A worker calls agent-service `POST /internal/v1/replies` and stores the AI message (see [ADR 0004](0004-realtime-and-jobs.md)).
- Every state change is one conditional `UPDATE … WHERE state = <expected>` in the same transaction as a `SYSTEM` message and an event. Two staff taking over at once: one wins, the other gets `409 Conflict`.
- `core.handoffs` stays as the record of each escalation; its status follows the conversation in the same transaction.
- agent-service keeps the LangGraph graph, checkpoints, RAG, the knowledge base, guardrails, evals and `agent.turns` (AI telemetry). `agent.conversations.id` is the same UUID as `core.conversations.id`. `POST /api/v1/chat` stays as an internal entry point for evals.
- The router is channel-agnostic: WhatsApp (T18) becomes one more inbound adapter that stores a message and lets the same router decide who answers.

## Alternatives considered

- **Keep conversations in agent-service and build the inbox there in Python.** Less data moves, but agent-service would grow users, roles, assignment and plans, the business rules ADR 0001 put in business-service. The AI service would stop being "AI only", and the domain logic would be split across two services.

## Consequences

Good:

- The AI service stays focused on AI, the original reason for two services (ADR 0001).
- One readable transcript per conversation, with customer, AI, staff and system messages, in the database that also holds handoffs and orders.
- WhatsApp needs no second inbox: its webhook is just another way to store a customer message.
- The part with most business logic is in the Java service, the stack the maintainer can explain most deeply.

Bad (accepted trade-off):

- AI replies become asynchronous, so we need a job queue and server push (ADR 0004).
- The demo chat moves from `/api/v1/chat` to the public API of business-service (T42); the old endpoint stays only for evals.
- Two services each keep part of a conversation (`core.messages` for the transcript, `agent.turns` and checkpoints for AI telemetry and memory). The shared UUID ties them together; there is no foreign key across schemas.
