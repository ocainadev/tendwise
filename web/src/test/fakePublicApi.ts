// A fake of business-service's public conversation API behind a mocked fetch,
// for the demo chat tests. It keeps the conversation's messages like the real
// server and gives the test a handle on each open event stream, so a test can
// "reply" (store a message and send message.created) or drop the connection.

import { vi } from 'vitest'
import type { PublicMessage, Sender } from '../api/publicApi'

export interface RecordedRequest {
  method: string
  url: string
  headers: Headers
  body: unknown
}

export interface FakeStream {
  headers: Headers
  isOpen: boolean
  // Sends one SSE event (or raw text when `raw` is true).
  send: (text: string) => void
  close: () => void
}

export interface FakePublicApi {
  requests: RecordedRequest[]
  streams: FakeStream[]
  messages: PublicMessage[]
  // Answers to use instead of the normal behaviour, by route.
  failNextSend: (status: number, body?: unknown) => void
  failNextStream: (status: number) => void
  // Stores a message (as the server would) without telling the streams.
  store: (sender: Sender, body: string, extra?: Partial<PublicMessage>) => PublicMessage
  // Stores a message and sends message.created on the open stream.
  reply: (sender: Sender, body: string, extra?: Partial<PublicMessage>) => PublicMessage
  // Sends an event on the newest open stream.
  emit: (type: string, data: Record<string, unknown>, id?: number) => void
  openStream: () => FakeStream | undefined
  requestsTo: (method: string, path: string) => RecordedRequest[]
}

export const CONVERSATION_ID = '11111111-2222-3333-4444-555555555555'
export const TOKEN = 'test-token'

const BASE = '/public/v1/conversations'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

export function installFakePublicApi(greeting = 'Hi! How can I help you today?'): FakePublicApi {
  let nextId = 1
  let sendFailure: Response | null = null
  let streamFailure: number | null = null

  const api: FakePublicApi = {
    requests: [],
    streams: [],
    messages: [],
    failNextSend(status, body = { title: 'Error', detail: `Failed with ${status}` }) {
      sendFailure = json(body, status)
    },
    failNextStream(status) {
      streamFailure = status
    },
    store(sender, body, extra = {}) {
      const message: PublicMessage = {
        id: nextId++,
        sender,
        body,
        createdAt: new Date().toISOString(),
        ...extra,
      }
      api.messages.push(message)
      return message
    },
    reply(sender, body, extra = {}) {
      const message = api.store(sender, body, extra)
      api.emit('message.created', { type: 'message.created', id: message.id }, message.id)
      return message
    },
    emit(type, data, id) {
      const stream = api.openStream()
      if (!stream) {
        throw new Error('No open event stream')
      }
      const idLine = id === undefined ? '' : `id: ${id}\n`
      stream.send(`event: ${type}\n${idLine}data: ${JSON.stringify({ conversationId: CONVERSATION_ID, ...data })}\n\n`)
    },
    openStream() {
      return [...api.streams].reverse().find((stream) => stream.isOpen)
    },
    requestsTo(method, path) {
      return api.requests.filter(
        (request) => request.method === method && request.url.split('?')[0] === path,
      )
    },
  }

  function openEventStream(headers: Headers, signal: AbortSignal | undefined): Response {
    if (streamFailure !== null) {
      const status = streamFailure
      streamFailure = null
      return json({ title: 'Error' }, status)
    }
    const encoder = new TextEncoder()
    let controller!: ReadableStreamDefaultController<Uint8Array>
    const body = new ReadableStream<Uint8Array>({
      start(c) {
        controller = c
      },
    })
    const stream: FakeStream = {
      headers,
      isOpen: true,
      send(text) {
        controller.enqueue(encoder.encode(text))
      },
      close() {
        if (stream.isOpen) {
          stream.isOpen = false
          controller.close()
        }
      },
    }
    signal?.addEventListener('abort', () => {
      if (stream.isOpen) {
        stream.isOpen = false
        controller.error(new DOMException('Aborted', 'AbortError'))
      }
    })
    api.streams.push(stream)
    stream.send(': connected\n\n')
    return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
  }

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = String(input)
    const method = (init.method ?? 'GET').toUpperCase()
    const headers = new Headers(init.headers)
    const body = typeof init.body === 'string' ? JSON.parse(init.body) : undefined
    api.requests.push({ method, url, headers, body })

    const [path, query = ''] = url.split('?')
    if (method === 'POST' && path === BASE) {
      return json({ conversationId: CONVERSATION_ID, token: TOKEN, greeting }, 201)
    }
    if (headers.get('Authorization') !== `Bearer ${TOKEN}`) {
      return json({ title: 'Conversation not found' }, 404)
    }
    if (method === 'POST' && path === `${BASE}/${CONVERSATION_ID}/messages`) {
      if (sendFailure) {
        const failure = sendFailure
        sendFailure = null
        return failure
      }
      const text = (body as { body: string }).body
      return json(api.store('CUSTOMER', text), 202)
    }
    if (method === 'GET' && path === `${BASE}/${CONVERSATION_ID}/messages`) {
      const after = Number(new URLSearchParams(query).get('after') ?? '0')
      return json(api.messages.filter((message) => message.id > after))
    }
    if (method === 'GET' && path === `${BASE}/${CONVERSATION_ID}/events`) {
      return openEventStream(headers, init.signal ?? undefined)
    }
    return json({ title: 'Not found' }, 404)
  })
  vi.stubGlobal('fetch', fetchMock)
  return api
}
