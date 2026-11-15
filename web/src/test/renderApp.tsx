// Test helpers for the whole app: a fake business-service behind a mocked
// fetch, and a render function that starts the router at a given URL.

import { QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router'
import { vi, type Mock } from 'vitest'
import type { Me, Membership } from '../api/auth'
import { App } from '../App'
import { createQueryClient } from '../auth/session'

export const CORRECT_PASSWORD = 'correct-password'

export function makeUser(memberships: Membership[], platformRole: Me['platformRole'] = null): Me {
  return {
    user: { id: 1, email: 'ana@example.test', name: 'Ana Souza' },
    platformRole,
    memberships,
  }
}

export const JOES_OWNER: Membership = { slug: 'joes-pizza', name: "Joe's Pizza", role: 'OWNER' }
export const JOES_AGENT: Membership = { slug: 'joes-pizza', name: "Joe's Pizza", role: 'AGENT' }
export const DENIM_AGENT: Membership = { slug: 'denim-corner', name: 'Denim Corner', role: 'AGENT' }

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const SESSION_ENDED = { status: 401, detail: 'Your session has ended. Please log in again.' }

interface FakeBackendOptions {
  // The user with a valid session when the app loads (null: logged out).
  loggedIn: Me | null
  // The user that POST /auth/login logs in with CORRECT_PASSWORD.
  loginAs?: Me
}

// Replaces fetch with a tiny in-memory business-service auth API.
export function fakeBackend({ loggedIn, loginAs }: FakeBackendOptions): Mock {
  let current = loggedIn
  const fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
    const method = init.method ?? 'GET'
    if (method === 'GET' && url === '/auth/me') {
      return current ? json(current) : json(SESSION_ENDED, 401)
    }
    if (method === 'POST' && url === '/auth/refresh') {
      return current ? json(current) : json(SESSION_ENDED, 401)
    }
    if (method === 'POST' && url === '/auth/login') {
      const body = JSON.parse(init.body as string)
      if (loginAs && body.password === CORRECT_PASSWORD) {
        current = loginAs
        return json(loginAs)
      }
      return json({ status: 401, detail: 'Invalid email or password.' }, 401)
    }
    if (method === 'POST' && url === '/auth/logout') {
      current = null
      return new Response(null, { status: 204 })
    }
    throw new Error(`Unexpected request in test: ${method} ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

// Shows the router's current URL, so tests can check redirects.
function CurrentPath() {
  const location = useLocation()
  return <output data-testid="current-path">{location.pathname}</output>
}

export function renderApp(path: string) {
  const user = userEvent.setup()
  render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <App />
        <CurrentPath />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { user }
}
