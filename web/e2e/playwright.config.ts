import { defineConfig, devices } from '@playwright/test'

// End-to-end tests against the running Docker stack (see ./README.md). They
// are not part of `npm test` or CI: they need the whole stack and a browser.
export default defineConfig({
  testDir: '.',
  timeout: 60_000,
  expect: { timeout: 20_000 },
  retries: 0,
  reporter: 'list',
  use: {
    // The compose web container. The demo businesses accept only this origin
    // (and 127.0.0.1:5173): allowedDomains in the seed.
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
