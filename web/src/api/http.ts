// fetch wrapper for business-service's cookie-authenticated routes
// (/auth/**, /api/v1/panel/**, /api/v1/admin/**). The session lives in
// HttpOnly cookies the browser sends by itself; this file only adds what the
// browser cannot do alone:
//
// - the CSRF header (a copy of the readable tendwise_csrf cookie) on every
//   state-changing request, as the double-submit check expects;
// - one automatic POST /auth/refresh when a request gets 401 (the 15-minute
//   access token expired), followed by a single retry of that request.

import { ApiError, type Problem } from './client'

const CSRF_COOKIE = 'tendwise_csrf'
const CSRF_HEADER = 'X-CSRF-Token'
const STATE_CHANGING_METHODS = ['POST', 'PUT', 'PATCH', 'DELETE']

// A 401 from these routes is final: refreshing would not help (login,
// logout) or would loop (refresh itself).
const NO_REFRESH_PATHS = ['/auth/login', '/auth/logout', '/auth/refresh']

export function readCookie(name: string): string | null {
  for (const part of document.cookie.split(';')) {
    const [key, ...value] = part.trim().split('=')
    if (key === name) {
      return decodeURIComponent(value.join('='))
    }
  }
  return null
}

function send(path: string, init: RequestInit): Promise<Response> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  if (STATE_CHANGING_METHODS.includes(method)) {
    // Read on every call: a refresh rotates the CSRF cookie.
    const csrfToken = readCookie(CSRF_COOKIE)
    if (csrfToken) {
      headers.set(CSRF_HEADER, csrfToken)
    }
  }
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  return fetch(path, { ...init, method, headers, credentials: 'same-origin' })
}

// business-service revokes the whole refresh token family when the same
// refresh token is used twice, so parallel 401s must share one refresh.
let refreshInFlight: Promise<boolean> | null = null

function refreshSession(): Promise<boolean> {
  if (refreshInFlight === null) {
    refreshInFlight = send('/auth/refresh', { method: 'POST' })
      .then((response) => response.ok)
      .catch(() => false)
      .finally(() => {
        refreshInFlight = null
      })
  }
  return refreshInFlight
}

// Sends the request and returns the response, whatever its status. Throws
// ApiError with status 0 only when the server could not be reached.
export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  try {
    const response = await send(path, init)
    if (response.status !== 401 || NO_REFRESH_PATHS.includes(path)) {
      return response
    }
    const refreshed = await refreshSession()
    return refreshed ? await send(path, init) : response
  } catch {
    throw new ApiError('Could not reach the server.', 0)
  }
}

// Throws ApiError for a non-2xx response. business-service errors are RFC 7807
// problems; the UI shows its own translated text, so only the status, the
// title and the invalid fields are kept.
export async function throwIfNotOk(response: Response): Promise<void> {
  if (response.ok) {
    return
  }
  let problem: Problem = {}
  try {
    const body = await response.json()
    problem = { title: body?.title, errors: Array.isArray(body?.errors) ? body.errors : undefined }
  } catch {
    // No JSON body (e.g. a proxy error page): the status is enough.
  }
  throw new ApiError(`HTTP ${response.status}`, response.status, problem)
}

// Like apiFetch, but returns the parsed JSON body and throws ApiError for any
// non-2xx status.
export async function apiJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await apiFetch(path, init)
  await throwIfNotOk(response)
  return (await response.json()) as T
}
