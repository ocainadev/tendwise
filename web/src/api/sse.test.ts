import { describe, expect, it, vi } from 'vitest'
import { ApiError } from './client'
import { readEventStream, SseParser, type SseEvent } from './sse'

describe('SseParser', () => {
  it('reads type, id and data, and skips comments', () => {
    const parser = new SseParser()

    const events = parser.push(
      ': connected\n\nevent: message.created\nid: 42\ndata: {"id":42}\n\n: heartbeat\n\n',
    )

    expect(events).toEqual([{ type: 'message.created', id: '42', data: '{"id":42}' }])
  })

  it('keeps an event that is split across chunks until it is complete', () => {
    const parser = new SseParser()

    expect(parser.push('event: ai.ty')).toEqual([])
    expect(parser.push('ping\ndata: {"type":')).toEqual([])
    expect(parser.push('"ai.typing"}\n')).toEqual([])
    expect(parser.push('\n')).toEqual([{ type: 'ai.typing', id: null, data: '{"type":"ai.typing"}' }])
  })

  it('accepts CRLF line endings, joins data lines and defaults the type to "message"', () => {
    const parser = new SseParser()

    const events = parser.push('data: first\r\ndata:second\r\n\r\n')

    expect(events).toEqual([{ type: 'message', id: null, data: 'first\nsecond' }])
  })

  it('does not deliver an event without data and resets fields between events', () => {
    const parser = new SseParser()

    const events = parser.push('event: lonely\nid: 1\n\nevent: resync\ndata: {}\n\n')

    expect(events).toEqual([{ type: 'resync', id: null, data: '{}' }])
  })
})

function streamOf(...chunks: string[]): Response {
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk))
      }
      controller.close()
    },
  })
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

describe('readEventStream', () => {
  it('sends the headers and delivers every event until the server closes the stream', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      streamOf('event: ai.typing\ndata: {}\n\n', 'event: message.created\nid: 7\ndata: {"id":7}\n\n'),
    )
    vi.stubGlobal('fetch', fetchMock)
    const events: SseEvent[] = []
    const onOpen = vi.fn()

    await readEventStream('/events', {
      headers: { Authorization: 'Bearer t', 'Last-Event-ID': '6' },
      signal: new AbortController().signal,
      onOpen,
      onEvent: (event) => events.push(event),
    })

    expect(onOpen).toHaveBeenCalledOnce()
    expect(events.map((event) => event.type)).toEqual(['ai.typing', 'message.created'])
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(init.headers).toEqual({
      Accept: 'text/event-stream',
      Authorization: 'Bearer t',
      'Last-Event-ID': '6',
    })
  })

  it('throws ApiError with the status when the server refuses the stream', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 404 })))

    const reading = readEventStream('/events', {
      headers: {},
      signal: new AbortController().signal,
      onEvent: () => {},
    })

    await expect(reading).rejects.toMatchObject({ name: 'ApiError', status: 404 })
  })

  it('throws ApiError with status 0 when the network fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    const reading = readEventStream('/events', {
      headers: {},
      signal: new AbortController().signal,
      onEvent: () => {},
    })

    await expect(reading).rejects.toBeInstanceOf(ApiError)
    await expect(reading).rejects.toMatchObject({ status: 0 })
  })

  it('stops quietly when aborted', async () => {
    const controller = new AbortController()
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init: RequestInit) => {
        controller.abort()
        return Promise.reject(init.signal?.reason ?? new DOMException('Aborted', 'AbortError'))
      }),
    )

    await expect(
      readEventStream('/events', { headers: {}, signal: controller.signal, onEvent: () => {} }),
    ).resolves.toBeUndefined()
  })
})
