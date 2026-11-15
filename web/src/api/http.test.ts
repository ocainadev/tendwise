import { afterEach, describe, expect, it, vi, type Mock } from 'vitest'
import { ApiError } from './client'
import { apiFetch } from './http'

function status(code: number): Response {
  return new Response(code === 204 ? null : '{}', { status: code })
}

function mockFetch(...responses: Response[]): Mock {
  const fetchMock = vi.fn()
  for (const response of responses) {
    fetchMock.mockResolvedValueOnce(response)
  }
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

// [method, url] of every fetch call, in order.
function calls(fetchMock: Mock): string[] {
  return fetchMock.mock.calls.map(([url, init]) => `${(init as RequestInit).method} ${url}`)
}

function sentHeaders(fetchMock: Mock, call: number): Headers {
  return (fetchMock.mock.calls[call][1] as RequestInit).headers as Headers
}

function setCsrfCookie(value: string) {
  document.cookie = `tendwise_csrf=${value}; path=/`
}

afterEach(() => {
  document.cookie = 'tendwise_csrf=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT'
})

describe('apiFetch', () => {
  it('sends the CSRF cookie as a header on state-changing requests only', async () => {
    setCsrfCookie('csrf-123')
    const fetchMock = mockFetch(status(200), status(200))

    await apiFetch('/api/v1/panel/joes-pizza/things', { method: 'POST', body: '{}' })
    await apiFetch('/api/v1/panel/joes-pizza/things')

    expect(sentHeaders(fetchMock, 0).get('X-CSRF-Token')).toBe('csrf-123')
    expect(sentHeaders(fetchMock, 0).get('Content-Type')).toBe('application/json')
    expect(sentHeaders(fetchMock, 1).get('X-CSRF-Token')).toBeNull()
    expect((fetchMock.mock.calls[0][1] as RequestInit).credentials).toBe('same-origin')
  })

  it('refreshes the session on 401 and retries the request once', async () => {
    const fetchMock = mockFetch(status(401), status(200), status(200))

    const response = await apiFetch('/auth/me')

    expect(response.status).toBe(200)
    expect(calls(fetchMock)).toEqual(['GET /auth/me', 'POST /auth/refresh', 'GET /auth/me'])
  })

  it('returns the 401 when the refresh fails, without a second retry', async () => {
    const fetchMock = mockFetch(status(401), status(401))

    const response = await apiFetch('/auth/me')

    expect(response.status).toBe(401)
    expect(calls(fetchMock)).toEqual(['GET /auth/me', 'POST /auth/refresh'])
  })

  it('retries with the CSRF token the refresh rotated', async () => {
    setCsrfCookie('old-token')
    const fetchMock = vi.fn()
    fetchMock.mockResolvedValueOnce(status(401))
    fetchMock.mockImplementationOnce(async () => {
      setCsrfCookie('new-token')
      return status(200)
    })
    fetchMock.mockResolvedValueOnce(status(200))
    vi.stubGlobal('fetch', fetchMock)

    await apiFetch('/api/v1/panel/joes-pizza/things', { method: 'POST', body: '{}' })

    expect(sentHeaders(fetchMock, 0).get('X-CSRF-Token')).toBe('old-token')
    expect(sentHeaders(fetchMock, 2).get('X-CSRF-Token')).toBe('new-token')
  })

  it('sends one refresh for several requests that get 401 at the same time', async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url === '/auth/refresh') {
        return status(200)
      }
      // Each request fails once (expired token), then succeeds.
      const earlier = fetchMock.mock.calls.filter(([calledUrl]) => calledUrl === url).length
      return status(earlier === 1 ? 401 : 200)
    })
    vi.stubGlobal('fetch', fetchMock)

    const responses = await Promise.all([apiFetch('/auth/me'), apiFetch('/api/v1/panel/a/b')])

    expect(responses.map((response) => response.status)).toEqual([200, 200])
    expect(calls(fetchMock).filter((call) => call === 'POST /auth/refresh')).toHaveLength(1)
  })

  it('does not refresh after a failed login', async () => {
    const fetchMock = mockFetch(status(401))

    const response = await apiFetch('/auth/login', { method: 'POST', body: '{}' })

    expect(response.status).toBe(401)
    expect(calls(fetchMock)).toEqual(['POST /auth/login'])
  })

  it('throws ApiError with status 0 when the server cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    const error = await apiFetch('/auth/me').catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(0)
  })
})
