import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

const BUSINESS_SERVICE = 'http://localhost:8080'
const AGENT_SERVICE = 'http://localhost:8000'

export default defineConfig({
  plugins: [react()],
  server: {
    // In development the browser talks to Vite only; Vite forwards the API
    // paths, so the page and the APIs share one origin (cookies, no CORS).
    // Same routing as nginx.conf. The first matching entry wins, so the
    // agent-service regex comes before the /api/v1/panel/ prefix. Paths that
    // are not listed (agent-service's /api/v1/chat, the service routes) are
    // not reachable from the browser, as in nginx.
    proxy: {
      // Public conversation API (demo chat, widget), with its SSE stream.
      '/public/': BUSINESS_SERVICE,
      '/auth/': BUSINESS_SERVICE,
      // Knowledge base routes of the panel (T50); a key starting with ^ is a regex.
      '^/api/v1/panel/[^/]+/documents(/|$)': AGENT_SERVICE,
      '/api/v1/panel/': BUSINESS_SERVICE,
      '/api/v1/admin/': BUSINESS_SERVICE,
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // The Playwright tests in e2e/ need the running stack (npm run e2e).
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
