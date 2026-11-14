# Control panels plan: platform admin and business inbox

Nov 13, 2026 · Status: **Approved 2026-11-13** (decisions in section 13)

This plan turns Tendwise from a demo into a product that can be sold. It adds two panels:

- **Platform admin** (for the Tendwise operator, who sells the system): create and manage client businesses, plans, usage, cost and margin, support access and audit.
- **Business panel** (for each client and their staff): see every conversation live, see the ones waiting for a person, take over from the AI, reply to the customer, hand the conversation back to the AI and resolve it. Also self-service for knowledge, catalog, settings and team.

This file keeps the product view and the reasoning. The contracts now live in [architecture.md](architecture.md) and ADRs [0002](adr/0002-conversations-in-business-service.md), [0003](adr/0003-panel-authentication.md) and [0004](adr/0004-realtime-and-jobs.md), and the backlog in [project-spec.md, section 11](project-spec.md#after-the-mvp-control-panels) (task T29). If this file and those docs ever disagree, they win.

## 1. Where we are (v0.1.0)

- The customer chats with agent-service (`POST /api/v1/chat`), which answers synchronously.
- A handoff is a row in `core.handoffs` with an AI-written summary. **Nobody can see it or answer the customer:** there is no staff UI, and the AI keeps answering after the handoff.
- Only the AI's replies are stored in `agent.turns`. The customer's messages exist only inside the LangGraph checkpoints, so there is no readable transcript.
- There are no users, logins or roles. The two services trust each other with one shared `X-Api-Key`.
- Businesses, catalog and knowledge come from SQL seeds and `data/businesses/<slug>/*.md`. Adding a client means editing files and redeploying.

These five gaps decide the plan.

## 2. Product view

### 2.1 Personas and roles

| Role | Who | Can do |
|---|---|---|
| `PLATFORM_ADMIN` | You (the operator) | Everything in the admin panel. Can enter any business in support mode (audited). |
| `OWNER` | The client (shop owner, manager) | Everything in their business: inbox, knowledge, catalog, orders, settings, team, plan and billing. |
| `AGENT` | The client's staff | Inbox (take over, reply, resolve), canned replies, read knowledge and catalog. No settings, team or billing. |

One user can belong to several businesses (for example an owner with two shops). The role is per business (`memberships` table).

### 2.2 Business panel (client): features

| Area | Features |
|---|---|
| **Inbox** (the core) | Tabs: *Precisa de você* (waiting for a person), *Comigo* (taken by me), *IA atendendo* (live AI conversations), *Resolvidas*. Real-time list with unread badges and a waiting timer. Transcript with customer, AI, staff and system messages. Side panel: handoff reason and AI summary, detected intent and language, sources the AI used, order codes mentioned. Actions: take over, reply, internal note, transfer to a colleague, hand back to the AI, resolve. |
| **Notifications** | Sound and tab badge on a new handoff, browser notification, email when nobody took a handoff after N minutes (later: mobile push). |
| **Canned replies** | Saved answers, inserted with `/` in the reply box. |
| **Knowledge** | List and edit the documents the AI answers from (markdown editor with preview), indexing status, a "test the assistant" playground. Later: PDF/photo upload (T19). |
| **Unanswered questions** | Questions the AI could not answer from the documents, grouped, with "add to FAQ". This is how the client improves the AI without help. |
| **Catalog** | Create, edit, mark unavailable. The AI's catalog document is generated from it. |
| **Orders** | List, search and update status by hand, or connect their system through the orders API with a business API key. |
| **Dashboard** | Conversations per day, % resolved by the AI alone, handoffs by reason, median time to the first human reply, top intents, languages, satisfaction. |
| **Settings** | Business profile, opening hours, languages, assistant name, tone and greeting, handoff rules, out-of-hours message. |
| **Website chat** | Colour and position, allowed domains, copy-paste install snippet. |
| **Team** | Invite by email, set role, remove. |
| **Plan** | Current plan, usage against limits, invoices (with billing). |

### 2.3 Platform admin (operator): features

| Area | Features |
|---|---|
| **Overview** | MRR, active and trial businesses, conversations this month, LLM cost, average cost per conversation, gross margin, p95 latency, error and blocked rates. |
| **Businesses** | List with status (trial, active, suspended, cancelled), plan, usage vs limit, LLM cost, last activity and health flags. Detail page with all of that over time. |
| **Onboarding wizard** | Business data → owner invite → upload/write knowledge → test chat → activate. Getting a new client live should take minutes. |
| **Support mode** | Open a business's panel as read-only support, time-boxed, with a visible banner and an audit entry. |
| **Plans** | Plans with price, monthly conversation limit, seat limit and features (WhatsApp, model). |
| **Health alerts** | Handoffs waiting for more than 2 hours (the client is losing customers), businesses over their limit, businesses with no conversations for 14 days (churn risk), error spikes. |
| **Quality** | Eval results per model, platform-wide unanswered questions, blocked messages by rule. |
| **Audit log** | Who did what: logins, support access, deletions, exports, role changes. |

**Unit economics, from the v0.1.0 report:** one conversation costs about US$0.0004 in LLM tokens. Even 10,000 conversations a month are about US$4. The LLM is not the cost to watch; infrastructure and support time are. The overview should show both.

## 3. The main change: conversations become domain data

Today agent-service owns the conversation and answers in the same HTTP request. A human reply cannot fit that model: it arrives minutes later, from someone else, and must be pushed to the customer.

**Decision ([ADR 0002](adr/0002-conversations-in-business-service.md)):** business-service owns conversations and messages, and routes each new customer message. agent-service becomes the AI responder behind an internal API.

```
               customer (website chat / later WhatsApp)
                          │  POST message, SSE events
                          ▼
┌─────────────────────────────────────────────────────────┐        ┌──────────────────────────┐
│ business-service  (domain + panels backend)             │ HTTP   │ agent-service (AI)        │
│                                                         │──────▶│ POST /internal/v1/replies │
│  conversations, messages, handoffs, users, businesses   │        │ RAG · guardrails · tools  │
│  message router:  state AI      → enqueue AI reply job  │◀──────│ GET/POST /handoffs, orders │
│                   state WAITING → store, notify staff   │ tools  │ knowledge base API (JWT)  │
│                   state HUMAN   → store, notify assignee│        └──────────────────────────┘
│  event bus: PostgreSQL LISTEN/NOTIFY → SSE streams      │
└─────────────────────────────────────────────────────────┘
                          ▲  REST + SSE (cookie auth)
                          │
               staff and operator (panels in web)
```

Why:

- Inbox, assignment, users and plans are business rules, the job business-service already has. The AI service stays focused on AI, the original reason for two services (ADR 0001).
- The router is channel-agnostic. WhatsApp (T18) becomes one more inbound adapter: its webhook stores a message and the same router decides who answers.
- A WhatsApp webhook must answer within seconds; an asynchronous AI reply (job + push) is required there anyway, so we build it once.
- business-service is the Java service, the stack you know best, so the part with most business logic is the part you can explain most deeply.

What stays in agent-service: the LangGraph graph, memory checkpoints, RAG and knowledge base, guardrails, `agent.turns` with tokens and cost (AI telemetry), evals. `POST /api/v1/chat` stays as an internal/eval entry point.

### 3.1 Conversation state machine

One field, `conversations.state`:

```
            handoff created (AI or customer asked)
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

- **AI:** each customer message enqueues an AI reply.
- **WAITING:** the AI does not answer. Messages are stored and staff are notified. If nobody takes over within `handoff_settings.autoAckMinutes` (default 3), or the business is closed, the customer gets one automatic message (for example "Já avisei a equipe; respondemos a partir das 9h. Quer deixar um e-mail ou WhatsApp?").
- **HUMAN:** only the assignee answers. The AI is silent.
- **Back to AI:** the messages exchanged with staff are sent to the agent with the next AI request, so the AI knows what was said.
- **RESOLVED:** if the customer writes again, the conversation reopens in `AI`. Staff can resolve from any state, `AI` included.
- `SYSTEM` messages the customer should see ("Maria joined", "back to the assistant", the auto-ack) have `internal = false`; staff-only ones (handoff reason, assignments) have `internal = true`.
- Every transition is one conditional `UPDATE … WHERE state = <expected>` in a transaction that also writes a `SYSTEM` message and an event. Two staff clicking "take over" at the same time: one wins, the other gets `409 Conflict` and sees who took it.
- `core.handoffs` stays as the record of each escalation (reason, summary, times). Its status follows the conversation in the same transaction: `WAITING` → `OPEN`, `HUMAN` → `IN_PROGRESS`, `RESOLVED`/back to AI → `RESOLVED`.

### 3.2 Message flow, AI state

1. The widget sends `POST /public/v1/conversations/{id}/messages`. business-service stores the `CUSTOMER` message, inserts an `AI_REPLY` job in the same transaction and answers `202` with the message.
2. A worker takes the job (`SELECT … FOR UPDATE SKIP LOCKED`), calls `POST /internal/v1/replies` on agent-service and stores the `AI` message with intent, language and sources in `metadata`.
3. If the agent created a handoff during that call, the state is already `WAITING` (the handoff endpoint changes it), and the reply is the fixed "a person will continue" text. If there was no handoff and staff took over while the AI was answering, the reply is dropped. `AI_REPLY` jobs of one conversation run one at a time.
4. Each stored message fires `NOTIFY`. Every business-service instance listening pushes it to the SSE streams of that conversation (widget) and that business (panel).
5. The widget shows "typing…" between steps 1 and 4. A failed job retries with backoff (3 attempts), then stores a `SYSTEM` message and moves the conversation to `WAITING`, so a person picks it up instead of the customer waiting forever.

The job queue lives in a PostgreSQL table: jobs survive restarts, there is no new infrastructure, and it is easy to explain. Redis or a broker come only if the load needs them.

## 4. Data model

All new `core` tables have `business_id` (except `users`, `refresh_tokens`, `password_resets`, `plans` and `jobs`) and every query filters by it. The task that creates each table and column is in [architecture.md](architecture.md#planned-core-tables-control-panels).

### 4.1 Schema `core` (business-service)

| Table | Main columns | Notes |
|---|---|---|
| `users` | id, email (unique, lowercase), password_hash, name, platform_role (`ADMIN` or null), totp_secret (encrypted, null), status (`ACTIVE`, `DISABLED`), failed_login_count, locked_until, created_at, last_login_at | Argon2id hashes |
| `memberships` | user_id, business_id, role (`OWNER`, `AGENT`), created_at | PK (user_id, business_id) |
| `invitations` | id, business_id, email, role, token_hash, invited_by, expires_at, accepted_at | Token sent by email, stored hashed, 7 days |
| `refresh_tokens` | id, user_id, token_hash, family_id, expires_at, revoked_at, created_at | Rotation with reuse detection |
| `password_resets` | id, user_id, token_hash, expires_at, used_at, created_at | Token sent by email, stored hashed, 1 hour |
| `plans` | id, code, name, price_cents, currency, conversation_limit, seat_limit, features (jsonb) | Editable by the operator |
| `businesses` (new columns) | status (`TRIAL`, `ACTIVE`, `SUSPENDED`, `CANCELLED`), plan_id, trial_ends_at, publishable_key, assistant_settings (jsonb), widget_settings (jsonb), handoff_settings (jsonb) | Settings are validated DTOs in Java, stored as jsonb |
| `conversations` | id (UUID), business_id, channel (`WEB`, `WHATSAPP`), state (`AI`, `WAITING`, `HUMAN`, `RESOLVED`), assignee_user_id, language, customer_ref, customer_contact (encrypted, null), contact_consent_at, token_hash, last_message_at, unread_by_staff, created_at, resolved_at, version | `customer_ref` is an anonymous id (web) or a phone hash (WhatsApp) |
| `messages` | id, conversation_id, business_id, sender (`CUSTOMER`, `AI`, `HUMAN`, `SYSTEM`), author_user_id, body, internal (bool), metadata (jsonb), created_at | `internal` = staff note, never sent to the customer |
| `handoffs` (new columns) | assignee_user_id, assigned_at, first_response_at, resolved_at, resolved_by | `first_response_at` feeds the response-time metric |
| `canned_replies` | id, business_id, shortcut, title, body, created_by | |
| `api_keys` | id, business_id, name, prefix, key_hash, scopes, last_used_at, revoked_at | For the orders integration; shown once on creation |
| `jobs` | id, type, payload (jsonb), status (`PENDING`, `RUNNING`, `DONE`, `FAILED`), attempts, run_at, locked_at, last_error, created_at | `AI_REPLY`, `SEND_EMAIL`, `USAGE_ROLLUP`, `AUTO_ACK` |
| `usage_daily` | business_id, day, conversations, customer_messages, ai_messages, human_messages, handoffs, resolved_by_ai, llm_cost_usd | Filled by a nightly job; feeds dashboards, limits and margin |
| `audit_log` | id, actor_user_id, business_id, action, target_type, target_id, metadata (jsonb), ip_hash, support_mode (bool), created_at | Append-only |
| `satisfaction` | conversation_id, business_id, rating (`UP`, `DOWN`), comment, created_at | Asked at the end of a conversation |

### 4.2 Schema `agent` (agent-service)

| Table | Change |
|---|---|
| `documents` | New columns `content` (text), `source` (`FILE`, `EDITOR`, `UPLOAD`, `CATALOG`), `status` (`PENDING`, `INDEXED`, `FAILED`), `updated_by`. Knowledge moves from files to the database; the `data/businesses` files stay as the seed of the demo businesses. |
| `jobs` | New: agent-service's own queue, same design as `core.jobs` (it never touches `core`). `INDEX_DOCUMENT`, `REGENERATE_CATALOG` |
| `knowledge_gaps` | New: id, business_id, question (phones and emails masked), best_similarity, created_at. One row per catalog question that found no chunk above the threshold. |
| `conversations`, `turns` | Unchanged: AI telemetry. The id is the same UUID as `core.conversations.id` (now created by business-service). `channel` also takes `playground` (knowledge-page test chats, which have no `core` conversation). |

## 5. APIs

Five families (public, panel, admin, auth, internal), kept apart by prefix, so each one has a single kind of authentication. The v0.1.0 service routes keep `X-Api-Key`, now limited to `/api/v1/businesses`, `/api/v1/orders` and `/api/v1/handoffs`.

### 5.1 Public API (website chat), business-service

Authentication: the business's **publishable key** (public by design; protected by allowed domains and rate limits) to start a conversation, then a **conversation token** (random 256 bits, stored hashed) for everything else.

| Method | Route | Description |
|---|---|---|
| POST | `/public/v1/conversations` | Body: `publishableKey`, `language` (optional). Returns `conversationId`, `token`, greeting |
| POST | `/public/v1/conversations/{id}/messages` | Customer message. `202` with the stored message. 429 on rate limit (per conversation and per IP) |
| GET | `/public/v1/conversations/{id}/messages?after=` | History (page reload, reconnect) |
| GET | `/public/v1/conversations/{id}/events` | SSE: `message.created`, `conversation.state_changed`, `staff.typing`, `ai.typing` |
| POST | `/public/v1/conversations/{id}/contact` | Customer leaves an email or phone, with consent, so staff can answer later |
| POST | `/public/v1/conversations/{id}/satisfaction` | Thumbs up or down |

The widget runs in an iframe on a third-party site, where cookies are blocked, so it reads the SSE stream with `fetch` and an `Authorization` header instead of `EventSource` (about 40 lines, no dependency). The token never goes in a URL.

### 5.2 Panel API, business-service

Authentication: session cookies (section 6). Every route has `/{slug}`, and a guard checks the user's membership and role **before** the controller runs. A business the user does not belong to answers `404`, the same as one that does not exist.

| Method | Route | Role |
|---|---|---|
| GET | `/api/v1/panel/{slug}/conversations?state=&assignee=&q=&cursor=` | AGENT |
| GET | `/api/v1/panel/{slug}/conversations/{id}` (with messages, handoffs, satisfaction) | AGENT |
| POST | `…/conversations/{id}/takeover` (409 if someone else has it) | AGENT |
| POST | `…/conversations/{id}/messages` (body, `internal`) | AGENT (must be the assignee, unless `internal`) |
| POST | `…/conversations/{id}/assign` (userId) | AGENT |
| POST | `…/conversations/{id}/release` (back to AI) | AGENT |
| POST | `…/conversations/{id}/resolve` | AGENT |
| POST | `…/conversations/{id}/read` | AGENT |
| POST | `…/conversations/{id}/typing` (sends `staff.typing`, stores nothing) | AGENT (assignee) |
| GET | `/api/v1/panel/{slug}/events` (SSE for the whole business) | AGENT |
| CRUD | `/api/v1/panel/{slug}/canned-replies` | AGENT read, OWNER write |
| CRUD | `/api/v1/panel/{slug}/catalog` | OWNER |
| GET, PATCH | `/api/v1/panel/{slug}/orders` (the integration API for a client's own system is proposed in T57) | OWNER |
| GET, PUT | `/api/v1/panel/{slug}/settings` | OWNER |
| CRUD | `/api/v1/panel/{slug}/team`, `/invitations` | OWNER |
| CRUD | `/api/v1/panel/{slug}/api-keys` | OWNER |
| GET | `/api/v1/panel/{slug}/dashboard?from=&to=` | OWNER |

Knowledge base endpoints live in agent-service, which verifies the same session token (section 6): `GET/POST/PUT/DELETE /api/v1/panel/{slug}/documents`, `POST …/documents/reindex`, `GET …/knowledge-gaps`, `POST …/playground` (chat with `channel = playground` in `agent.conversations`, kept out of the metrics).

### 5.3 Admin API, business-service (`platform_role = ADMIN`)

| Method | Route |
|---|---|
| GET, POST | `/api/v1/admin/businesses` (create sends the owner invitation) |
| GET, PATCH | `/api/v1/admin/businesses/{slug}` (status and plan; limits come from the plan) |
| POST | `/api/v1/admin/businesses/{slug}/support-session` (read-only, 30 min) |
| CRUD | `/api/v1/admin/plans` |
| GET | `/api/v1/admin/overview?from=&to=`, `/api/v1/admin/usage?groupBy=business` |
| GET | `/api/v1/admin/alerts`, `/api/v1/admin/audit-log` |

### 5.4 Auth API, business-service

`POST /auth/login`, `POST /auth/logout`, `POST /auth/refresh`, `GET /auth/me` (user, platform role, memberships), `GET /auth/jwks.json`, `POST /auth/invitations/accept` (token in the body, never in the path), `POST /auth/password-reset`, `POST /auth/password-reset/confirm`, `POST /auth/2fa/setup`, `POST /auth/2fa/verify`.

### 5.5 Internal API (service to service)

- agent-service `POST /internal/v1/replies`: in `conversation_id`, `business_slug`, `message`, `staff_messages[]` (exchanged since the last AI turn); out `reply`, `intent`, `language`, `sources`, `handoff`, `order_code` (for the "orders mentioned" side panel). Protected by the existing service key (`X-Api-Key` with `BUSINESS_SERVICE_API_KEY`) and not exposed by nginx.
- agent-service `GET /internal/v1/usage?from=&to=` per business (tokens, cost) for the nightly rollup, so business-service never reads the `agent` schema.
- The existing business-service routes used by the agent's tools stay as they are.

## 6. Security and privacy

- **Login:** email and password (Argon2id), 15-minute lockout after 5 consecutive failures, generic error messages. Mandatory TOTP 2FA for platform admins, optional for owners.
- **Tokens:** business-service signs a short access token (JWT, 15 minutes, Ed25519) with the user id, platform role and memberships. It goes in an `HttpOnly; Secure; SameSite=Lax` cookie, together with a refresh token (30 days, rotated on every use; a reused refresh token revokes the whole family). agent-service verifies the access token with business-service's public key (JWKS), so it can serve the knowledge base without proxy code. Disabling a user revokes their refresh tokens, so access ends in 15 minutes at most.
- **CSRF:** with cookie auth, every state-changing request also needs an `X-CSRF-Token` header (double-submit cookie), checked by both services.
- **Tenant isolation:** the business comes from the URL slug checked against the token's memberships, never from the body. Every repository method takes `businessId`. A shared test suite tries every panel route with a user from another business and expects 404. Optionally later: PostgreSQL row-level security as a second line of defence.
- **Support mode:** a separate, read-only, 30-minute token, a banner on every page and an audit entry when it starts and for each page viewed.
- **Personal data (LGPD/GDPR):** staff see messages (they have to answer them), but logs never contain message text, phone numbers or emails. The customer's contact is stored only with consent, encrypted at rest. The retention job also purges `core.messages` and `core.conversations`. An owner can export or delete one customer's conversations (data subject request). The audit log stores a hash of the IP, not the IP.
- **Public endpoints:** allowed domains per business (CORS and `frame-ancestors`), rate limits per IP and per conversation, message size limits. The per-conversation rate limit moves from agent-service memory to business-service's database (counted from `core.messages`), so it works with more than one instance; the per-IP limit stays in memory until there is more than one instance.
- **Merchant-provided text** (assistant tone, extra instructions, documents) goes into prompts inside delimited, length-capped sections, below the system rules, and the eval suite gets cases for it.

## 7. Real-time design

- **Transport: Server-Sent Events.** Messages to the server are normal `POST`s; only the server→browser direction needs push. SSE is plain HTTP, reconnects by itself, resumes with `Last-Event-ID`, and goes through nginx with `proxy_buffering off`.
- **Fan-out: PostgreSQL `LISTEN/NOTIFY`.** After the commit, business-service sends `NOTIFY events, '{"businessId":…,"conversationId":…,"type":…,"id":…}'`. Every instance listens on one dedicated connection and forwards the event to its open SSE connections that match the business or conversation. It works with several instances and needs no Redis. The payload carries ids only; the browser fetches anything bigger.
- **Server:** Spring MVC `SseEmitter` with Java 21 virtual threads (`spring.threads.virtual.enabled=true`), so thousands of open connections do not need thousands of platform threads. Heartbeat comment every 25 seconds.
- **Events:** `message.created`, `conversation.state_changed`, `conversation.assigned`, `conversation.read`, `handoff.created`, `staff.typing`, `ai.typing`.
- **Missed events:** on reconnect the client sends `Last-Event-ID`. If the gap is too big, the client refetches the list. The database stays the source of truth; events are only hints.

## 8. Front-end design

- **One app, three areas** in `web`, with React Router: `/app/:slug/*` business panel, `/admin/*` platform admin, `/demo` the current demo chat. The login is shared, and after it the user goes to their area.
- **The website chat widget** is a separate small bundle (`widget/`, plain TypeScript, no React) that loads in an iframe, so the merchant's CSS cannot break it and ours cannot break their site. The `/demo` page becomes a page that embeds the real widget.
- **Proposed dependencies** (each justified in its PR): `react-router` (routing), `@tanstack/react-query` (server state, cache and refetch after events), `tailwindcss` plus copied shadcn/ui components on Radix (accessible primitives without a heavy UI framework), `recharts` (dashboards), `react-i18next` (UI in **pt-BR first**, en and es later), Playwright (end-to-end tests of the handoff flow).

Inbox layout (desktop; on mobile each column is a screen):

```
┌──────────────┬──────────────────────────────────────┬──────────────────────┐
│ Precisa de   │ Ana · Web · pt · esperando há 04:12   │ Handoff              │
│ você (3)  ●  │──────────────────────────────────────│ Motivo: reclamação   │
│ Comigo (1)   │ 👤 Meu pedido DC-2003 veio errado      │ Resumo da IA:        │
│ IA (12)      │ 🤖 Sinto muito! Passei para a equipe…  │ "Cliente recebeu…"   │
│ Resolvidas   │ ⚙ Handoff criado: COMPLAINT           │                      │
│──────────────│                                       │ Pedidos citados      │
│ ● Ana  04:12 │                                       │ DC-2003 (a caminho)  │
│   Bruno 01:30│                                       │                      │
│   Carla 00:20│──────────────────────────────────────│ Fontes usadas pela IA│
│              │ [ Assumir conversa ]                   │ policies.md › Trocas │
│              │ / respostas prontas · nota interna     │                      │
└──────────────┴──────────────────────────────────────┴──────────────────────┘
```

## 9. Agent changes

- `POST /internal/v1/replies` (section 5.5). `staff_messages` are added to the history before the turn, so the AI does not contradict what a person said.
- **Per-business assistant settings** (name, tone, greeting, extra instructions, enabled languages), read through `GET /businesses/{slug}` and placed in a delimited prompt section.
- **Business hours in the handoff reply:** outside opening hours, the handoff reply says when the team is back and offers to collect a contact.
- **Knowledge gaps:** the retrieve node records questions with no chunk above the threshold.
- **Knowledge base from the database**, with re-indexing per business after each edit (an `INDEX_DOCUMENT` job in `agent.jobs`, so saving is instant).
- **Catalog document** generated from the catalog API (`GET /businesses/{slug}/catalog`, so the agent still never reads `core`) whenever the catalog changes, so the catalog has one source of truth.
- **Playground:** the `create_handoff` node only reports the handoff it would create; it does not call business-service.
- **Model per plan** (for example a cheaper model on the entry plan), through the existing `LLM_MODEL` setting made per business. No task yet.
- **Later: copilot.** `POST /internal/v1/suggest-reply` drafts an answer for staff from the transcript and the knowledge base. Staff edit it before sending.

## 10. Metrics definitions

| Metric | Definition |
|---|---|
| Resolved by AI (containment) | Conversations resolved, or idle for 24 h, with no handoff ÷ all conversations in the period |
| Handoff rate | Conversations with at least one handoff ÷ all conversations |
| First human response time | `handoffs.first_response_at − handoffs.created_at`, median and p90 |
| Waiting now | Conversations in `WAITING`, with the oldest wait |
| Satisfaction | 👍 ÷ (👍 + 👎) |
| Cost per conversation | Σ `agent.turns.cost_usd` ÷ conversations |
| Gross margin per business | Plan price − LLM cost − share of fixed infrastructure (a setting) |
| MRR | Σ monthly plan price of `ACTIVE` businesses |

## 11. Testing strategy

- business-service: Testcontainers for every repository and every state transition, including two concurrent takeovers (one 409). A tenant isolation suite runs every panel route as a user of another business. SSE is tested with an HTTP client that reads the stream.
- agent-service: `/internal/v1/replies` with the LLM mocked, staff messages in the history, knowledge base CRUD and the JWT checks.
- web: Vitest for components and hooks, with the API mocked.
- **End to end (Playwright, in CI with docker compose and a fake LLM):** the customer asks for a person → the handoff appears in the inbox within 2 seconds → staff takes over and replies → the customer sees the reply → staff hands back to the AI → the next answer comes from the AI.
- Evals: new cases for merchant instructions that try to break the rules, and for the AI after staff messages.

## 12. Backlog

Moved to [project-spec.md, section 11 "After the MVP: control panels"](project-spec.md#after-the-mvp-control-panels) (T28–T73, with tracks, dependencies, acceptance criteria and the fastest path to the first paying client). It replaces T17, T22 and T23; T18–T21 are placed in its phases 7 and 8.

## 13. Decisions (approved 2026-11-13)

All seven recommendations were approved. The alternatives that were considered are kept in the ADRs.

| # | Decision | Chosen | Recorded in |
|---|---|---|---|
| 1 | Who owns conversations | **business-service** owns conversations and messages and routes each customer message; agent-service is the AI responder behind `POST /internal/v1/replies` | [ADR 0002](adr/0002-conversations-in-business-service.md) |
| 2 | Authentication | **Own login in business-service** (Spring Security): short Ed25519 JWT access token and a rotating refresh token in HttpOnly cookies, JWKS so agent-service verifies the same token, CSRF double-submit | [ADR 0003](adr/0003-panel-authentication.md) |
| 3 | AI replies | **Asynchronous** (202 + SSE), with a PostgreSQL job table (`SKIP LOCKED`) and `LISTEN/NOTIFY` fan-out | [ADR 0004](adr/0004-realtime-and-jobs.md) |
| 4 | Customer who left the site | **Ask for an email or WhatsApp number at handoff time, with consent** | [architecture.md](architecture.md#public-api-planned) (contact endpoint, T41; widget form, T56) |
| 5 | Panel language | **pt-BR first**, i18n ready for en and es | T33 |
| 6 | Billing provider | **Decided at T23**: one that supports Pix and boleto if the first clients are in Brazil; manual invoices until then | T23 |
| 7 | Hosting | **Decided at T20** with a cost estimate (one small VM with docker compose is enough for the first clients) | T20 |

### Changes made while writing the contracts (T29)

Small inconsistencies found when the plan was turned into `architecture.md`; the sections above already include them.

- **API families:** five (public, panel, admin, auth, internal), not three. The `X-Api-Key` filter, which today covers every `/api/**` path, is narrowed to the v0.1.0 service routes in T32, so the cookie-based panel and admin routes are not blocked by it.
- **Event name:** the widget stream uses `conversation.state_changed`, the name in section 7 (it said `state.changed`).
- **Playground:** it lives in agent-service, so `PLAYGROUND` left `core.conversations.channel`; `agent.conversations.channel` takes `playground`, and a playground handoff is not sent to business-service. The endpoint has a task now (T51 is P+W).
- **Agent jobs:** `INDEX_DOCUMENT` needs a queue agent-service may use, so it gets its own `agent.jobs` (T50). The catalog document is built from the catalog API, because the agent never reads `core`.
- **Login storage:** lockout needs `users.failed_login_count` and `users.locked_until` (rule: 15-minute lock after 5 consecutive failures). Password reset had no token table or task: `password_resets` and its endpoints go to T69.
- **Tokens out of paths:** invitation accept is `POST /auth/invitations/accept` with the token in the body. The JWKS endpoint is named `GET /auth/jwks.json`.
- **Missing inputs:** `staff.typing` needs `POST …/conversations/{id}/typing` (T44); the "orders mentioned" side panel needs `order_code` from `/internal/v1/replies`.
- **`business_id` exceptions** also include `refresh_tokens`, `password_resets` and `jobs`.
- **Admin `PATCH`** changes status and plan only; there are no per-business limit columns.
- **Tasks for orphan pieces:** contact endpoint → T41 (widget form in T56, which now depends on T41); team API → T34; dashboard API → T60 (now J+W); `audit_log` table → T44, its first writer (T64 adds the read API); `widget_settings` → T38 (its allowed-domains test needs it); `handoff_settings` → T41.
- **State machine details:** staff can resolve from `AI` too (the containment metric counts conversations resolved without a handoff); `SYSTEM` messages use `internal` to say whether the customer sees them; an AI reply is dropped if staff took over while it was being written; `AI_REPLY` jobs of one conversation run one at a time; `PATCH /handoffs/{id}` is removed in T44, since the status now follows the conversation.
- **Rate limits:** per conversation, counted from `core.messages`; per IP, in memory until there is more than one instance.
- **Backlog:** phases 7 and 8 got acceptance criteria like the other phases; model per plan has no task yet and is marked deferred.
