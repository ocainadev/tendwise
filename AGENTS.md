# AGENTS.md

## Context
Tendwise: an AI customer-service clerk for any small business.
Source of truth: docs/architecture.md and the task backlog (docs/project-spec.md, section 11).
Monorepo: business-service (Java 21/Spring Boot), agent-service (Python 3.12/FastAPI/LangGraph), web (React/Vite/TS).
Everything in English: code, comments, commits, PRs, docs.

## Workflow
1. Work on ONE task at a time, identified by its ID (e.g. T07).
2. Branch from an up-to-date main:
   git switch main && git pull && git switch -c feat/T07-rag-ingestion
3. Read the acceptance criteria before writing code. If anything is ambiguous, stop and ask.
4. Make small Conventional Commits with the footer "Refs: T07".
5. Run lint and tests before every push.
6. Open the PR using the template. Never merge yourself: the Auto-merge workflow squash-merges the PR and deletes its branch once CI is green. Open it as a draft if it must not be merged yet.

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
- Database only: docker compose up db
- business-service: cd business-service && ./mvnw verify
- agent-service: cd agent-service && uv run ruff check . && uv run pytest
- web: cd web && npm run lint && npm test
- Ingestion: cd agent-service && uv run python scripts/ingest.py
- Evals: cd agent-service && uv run python evals/run_evals.py
