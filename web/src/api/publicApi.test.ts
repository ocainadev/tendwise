import { describe, expect, it, vi } from 'vitest'
import { fetchHistory, HISTORY_PAGE_SIZE, sendCustomerMessage, type PublicMessage } from './publicApi'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function messages(firstId: number, count: number): PublicMessage[] {
  return Array.from({ length: count }, (_, index) => ({
    id: firstId + index,
    sender: 'AI',
    body: `Message ${firstId + index}`,
    createdAt: '2026-11-16T10:00:00Z',
  }))
}

describe('fetchHistory', () => {
  it('asks again with the last id while the pages are full', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(messages(6, HISTORY_PAGE_SIZE)))
      .mockResolvedValueOnce(jsonResponse(messages(6 + HISTORY_PAGE_SIZE, 2)))
    vi.stubGlobal('fetch', fetchMock)

    const found = await fetchHistory('conv-1', 'tok', 5)

    expect(found).toHaveLength(HISTORY_PAGE_SIZE + 2)
    expect(fetchMock.mock.calls[0][0]).toBe('/public/v1/conversations/conv-1/messages?after=5')
    expect(fetchMock.mock.calls[1][0]).toBe(
      `/public/v1/conversations/conv-1/messages?after=${5 + HISTORY_PAGE_SIZE}`,
    )
    expect((fetchMock.mock.calls[0][1] as RequestInit).headers).toEqual({ Authorization: 'Bearer tok' })
  })
})

describe('sendCustomerMessage', () => {
  it('shows the problem detail of an error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ title: 'Too many requests', detail: 'Please wait.' }, 429)),
    )

    await expect(sendCustomerMessage('conv-1', 'tok', 'Hi')).rejects.toMatchObject({
      status: 429,
      message: 'Please wait.',
    })
  })

  it('explains a proxy error when business-service is down', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Bad gateway', { status: 502 })))

    await expect(sendCustomerMessage('conv-1', 'tok', 'Hi')).rejects.toMatchObject({
      status: 502,
      message: 'The chat service is not available right now. Please try again in a moment.',
    })
  })
})
