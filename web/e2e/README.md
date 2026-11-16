# End-to-end tests (Playwright)

Browser tests of the demo chat (`/demo`) against the whole Docker stack:
web (nginx), business-service, agent-service and PostgreSQL. agent-service
answers through a fake LLM (`fake-llm/server.mjs`, a tiny OpenAI-compatible
server), so no LLM key is needed and the answers are predictable.

They are not part of `npm test` or CI: they need the stack and a browser.

## Run them

From the repository root, start the stack with the fake LLM. This replaces
the running `tendwise-*` containers; `docker compose up -d --build` without
the second file brings back the normal stack with your real LLM settings.

```bash
docker compose -f docker-compose.yml -f web/e2e/docker-compose.e2e.yml up -d --build
```

Wait until `curl -s localhost:8000/health` answers (the first start downloads
the embedding model), then, once per machine, install the browser and run the
tests:

```bash
cd web && npx playwright install chromium
```

```bash
cd web && npm run e2e
```

The tests open `http://localhost:5173` (the compose `web` container): the demo
businesses accept only that origin and `http://127.0.0.1:5173`.
