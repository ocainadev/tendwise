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
    // business-service prefixes come before the catch-all /api.
    proxy: {
      '/auth/': BUSINESS_SERVICE,
      // Knowledge base routes of the panel (T50); a key starting with ^ is a regex.
      '^/api/v1/panel/[^/]+/documents(/|$)': AGENT_SERVICE,
      // Knowledge page test chat (T51).
      '^/api/v1/panel/[^/]+/playground$': AGENT_SERVICE,
      '/api/v1/panel/': BUSINESS_SERVICE,
      '/api/v1/admin/': BUSINESS_SERVICE,
      '/api': AGENT_SERVICE,
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
})
