import { describe, expect, it, vi } from 'vitest'
import { ApiError, sendMessage, type ChatResponse } from './client'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const request = { conversation_id: null, business_slug: 'joes-pizza', message: 'Hi' }

const okBody: ChatResponse = {
  conversation_id: 'conv-1',
  reply: 'Hello!',
  intent: 'other',
  language: 'en',
  sources: [],
  handoff: false,
}

describe('sendMessage', () => {
  it('posts the request as JSON to /api/v1/chat and returns the reply', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(okBody))
    vi.stubGlobal('fetch', fetchMock)

    const response = await sendMessage(request)

    expect(response).toEqual(okBody)
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    })
  })

  it('throws an ApiError with the detail message on a non-2xx response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ detail: 'Unknown business' }, 404)))

    const error = await sendMessage(request).catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ message: 'Unknown business', status: 404 })
  })

  it('uses the first message of a validation error list', async () => {
    const body = { detail: [{ msg: 'String should have at most 1000 characters' }] }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(body, 422)))

    await expect(sendMessage(request)).rejects.toThrow('String should have at most 1000 characters')
  })

  it('explains a 429 that has no detail', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 429 })))

    await expect(sendMessage(request)).rejects.toThrow('Too many messages')
  })

  it('says the service is unavailable when the proxy cannot reach it', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Bad gateway', { status: 502 })))

    await expect(sendMessage(request)).rejects.toThrow('The chat service is not available right now')
  })

  it('falls back to the status code when the body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Oops', { status: 500 })))

    await expect(sendMessage(request)).rejects.toThrow('HTTP 500')
  })

  it('throws a connection error when the server cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    await expect(sendMessage(request)).rejects.toMatchObject({ status: 0 })
  })
})
