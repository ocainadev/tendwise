import { screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  ConversationDetail,
  ConversationItem,
  ConversationState,
  InboxMessage,
} from '../api/inbox'
import { JOES_AGENT, fakeBackend, makeUser, renderApp } from '../test/renderApp'
import { reconnect } from './usePanelEvents'

const BASE = '/api/v1/panel/joes-pizza/conversations'
const EVENTS = '/api/v1/panel/joes-pizza/events'
const ME_ID = 1 // makeUser's user

const ANA = 'aaaa1111-0000-0000-0000-000000000001'
const BRUNO = 'bbbb2222-0000-0000-0000-000000000002'
const CARLA = 'cccc3333-0000-0000-0000-000000000003'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function minutesAgo(minutes: number): string {
  return new Date(Date.now() - minutes * 60_000).toISOString()
}

let nextMessageId = 100

function message(sender: InboxMessage['sender'], body: string, extra: Partial<InboxMessage> = {}): InboxMessage {
  return {
    id: nextMessageId++,
    sender,
    authorUserId: null,
    authorName: null,
    body,
    internal: false,
    metadata: null,
    createdAt: minutesAgo(5),
    ...extra,
  }
}

function conversation(id: string, state: ConversationState, messages: InboxMessage[]): ConversationDetail {
  return {
    id,
    state,
    channel: 'WEB',
    language: 'pt',
    assignee: state === 'HUMAN' ? { id: ME_ID, name: 'Ana' } : null,
    unreadCount: 0,
    createdAt: minutesAgo(10),
    lastMessageAt: minutesAgo(5),
    resolvedAt: null,
    waitingSince: state === 'WAITING' ? new Date(Date.now() - 252_000).toISOString() : null,
    messages,
    handoffs: [],
  }
}

// The conversation of the plan's mock-up: a complaint handed to staff.
function anaComplaint(): ConversationDetail {
  return {
    ...conversation(ANA, 'WAITING', [
      message('CUSTOMER', 'Meu pedido DC-2003 veio errado'),
      message('AI', 'Sinto muito! Passei para a equipe.', {
        metadata: {
          intent: 'complaint',
          language: 'pt',
          orderCode: 'DC-2003',
          sources: [{ title: 'Políticas', section: 'Trocas' }],
        },
      }),
      message('SYSTEM', '', { internal: true, metadata: { event: 'HANDOFF_CREATED', reason: 'COMPLAINT' } }),
      message('HUMAN', 'Vou verificar o pedido.', { internal: true, authorUserId: 2, authorName: 'Maria' }),
      message('HUMAN', 'Oi! Já estou vendo seu pedido.', { authorUserId: 2, authorName: 'Maria' }),
    ]),
    unreadCount: 2,
    handoffs: [
      {
        id: 1,
        reason: 'COMPLAINT',
        summary: 'Cliente recebeu o pedido errado.',
        status: 'OPEN',
        createdAt: minutesAgo(4),
        updatedAt: minutesAgo(4),
      },
    ],
  }
}

interface FakeStream {
  headers: Headers
  send: (type: string, data: Record<string, unknown>, id?: number) => void
  close: () => void
}

interface FakeInboxApi {
  conversations: ConversationDetail[]
  streams: FakeStream[]
  // Answers every inbox request (not the event stream) with this status.
  failWith: number | null
  // Page size of the list.
  pageSize: number
  requests: string[]
}

function toItem(detail: ConversationDetail): ConversationItem {
  const last = [...detail.messages].reverse().find((m) => m.sender !== 'SYSTEM')
  return {
    id: detail.id,
    state: detail.state,
    channel: detail.channel,
    language: detail.language,
    assignee: detail.assignee,
    lastMessage: last ? { sender: last.sender, preview: last.body.slice(0, 120), internal: last.internal } : null,
    lastMessageAt: detail.lastMessageAt,
    unreadCount: detail.unreadCount,
    handoffReason: detail.handoffs.at(-1)?.reason ?? null,
    waitingSince: detail.waitingSince,
  }
}

function tabCount(list: ConversationDetail[]) {
  return { conversations: list.length, unread: list.filter((c) => c.unreadCount > 0).length }
}

// The auth API of fakeBackend plus an in-memory inbox API and event stream.
function fakeInboxApi(conversations: ConversationDetail[]): FakeInboxApi {
  const authFetch = fakeBackend({ loggedIn: makeUser([JOES_AGENT]) })
  const api: FakeInboxApi = { conversations, streams: [], failWith: null, pageSize: 30, requests: [] }

  function openStream(headers: Headers, signal: AbortSignal | undefined): Response {
    const encoder = new TextEncoder()
    let controller!: ReadableStreamDefaultController<Uint8Array>
    let open = true
    const body = new ReadableStream<Uint8Array>({ start: (c) => (controller = c) })
    api.streams.push({
      headers,
      send(type, data, id) {
        const idLine = id === undefined ? '' : `id: ${id}\n`
        controller.enqueue(encoder.encode(`event: ${type}\n${idLine}data: ${JSON.stringify({ type, ...data })}\n\n`))
      },
      close() {
        if (open) {
          open = false
          controller.close()
        }
      },
    })
    signal?.addEventListener('abort', () => {
      if (open) {
        open = false
        controller.error(new DOMException('Aborted', 'AbortError'))
      }
    })
    controller.enqueue(encoder.encode(': connected\n\n'))
    return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
  }

  const fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url === EVENTS) {
      return openStream(new Headers(init.headers), init.signal ?? undefined)
    }
    if (!url.startsWith(BASE)) {
      return authFetch(url, init)
    }
    api.requests.push(url)
    if (api.failWith) {
      return json({ detail: 'down' }, api.failWith)
    }
    const [path, query = ''] = url.split('?')
    if (path === BASE) {
      const params = new URLSearchParams(query)
      const all = api.conversations
      let found = all.filter((c) => c.state === params.get('state'))
      if (params.get('assignee') === 'me') {
        found = found.filter((c) => c.assignee?.id === ME_ID)
      }
      const start = Number(params.get('cursor') ?? 0)
      const page = found.slice(start, start + api.pageSize)
      const end = start + page.length
      return json({
        items: page.map(toItem),
        nextCursor: end < found.length ? String(end) : null,
        counts: {
          waiting: tabCount(all.filter((c) => c.state === 'WAITING')),
          mine: tabCount(all.filter((c) => c.state === 'HUMAN' && c.assignee?.id === ME_ID)),
          ai: tabCount(all.filter((c) => c.state === 'AI')),
        },
      })
    }
    const detail = api.conversations.find((c) => path === `${BASE}/${c.id}`)
    return detail ? json(detail) : json({ detail: 'Conversation not found' }, 404)
  })
  vi.stubGlobal('fetch', fetchMock)
  return api
}

function tab(name: RegExp) {
  return screen.getByRole('link', { name })
}

async function conversationList() {
  return within(await screen.findByRole('region', { name: 'Caixa de entrada' }))
}

async function transcript() {
  return within(await screen.findByRole('region', { name: 'Mensagens da conversa' }))
}

async function context() {
  return within(await screen.findByRole('complementary', { name: 'Contexto da conversa' }))
}

beforeEach(() => {
  reconnect.delaysMs = [10]
})

afterEach(() => {
  reconnect.delaysMs = [1000, 2000, 5000, 10000, 30000]
})

describe('inbox list', () => {
  it('shows the tabs with their counts and the waiting conversations', async () => {
    fakeInboxApi([
      anaComplaint(),
      conversation(BRUNO, 'WAITING', [message('CUSTOMER', 'Quero falar com uma pessoa')]),
      conversation(CARLA, 'HUMAN', [message('CUSTOMER', 'Obrigada!')]),
      conversation('dddd', 'AI', []),
    ])
    renderApp('/app/joes-pizza/inbox')

    await waitFor(() => expect(tab(/Precisa de você/)).toHaveTextContent('Precisa de você(2)'))
    expect(tab(/Precisa de você/)).toHaveAttribute('aria-current', 'page')
    expect(within(tab(/Precisa de você/)).getByRole('img')).toHaveAccessibleName(
      '1 conversa com mensagens não lidas',
    )
    expect(tab(/Comigo/)).toHaveTextContent('Comigo(1)')
    expect(tab(/IA atendendo/)).toHaveTextContent('IA atendendo(1)')
    expect(tab(/Resolvidas/)).toHaveTextContent(/^Resolvidas$/)

    const ana = (await conversationList()).getByRole('link', { name: /Cliente anônimo #AAAA/ })
    expect(ana).toHaveTextContent('Site · PT')
    expect(ana).toHaveTextContent('Equipe: Oi! Já estou vendo seu pedido.')
    expect(ana).toHaveTextContent(/esperando há 04:1\d/)
    expect(within(ana).getByRole('img')).toHaveAccessibleName('2 mensagens não lidas')
    const bruno = (await conversationList()).getByRole('link', { name: /Cliente anônimo #BBBB/ })
    expect(within(bruno).queryByRole('img')).not.toBeInTheDocument()
    expect(screen.getByText('Escolha uma conversa para ver as mensagens.')).toBeInTheDocument()
  })

  it('opens another tab from the URL', async () => {
    fakeInboxApi([anaComplaint(), conversation(CARLA, 'HUMAN', [message('CUSTOMER', 'Obrigada!')])])
    renderApp('/app/joes-pizza/inbox?tab=mine')

    expect(await (await conversationList()).findByRole('link', { name: /#CCCC/ })).toBeInTheDocument()
    expect(tab(/Comigo/)).toHaveAttribute('aria-current', 'page')
    expect((await conversationList()).queryByRole('link', { name: /#AAAA/ })).not.toBeInTheDocument()
  })

  it('shows an empty state for each tab', async () => {
    fakeInboxApi([])
    const { user } = renderApp('/app/joes-pizza/inbox')

    expect(await screen.findByText('Ninguém está esperando por você agora.')).toBeInTheDocument()
    await user.click(tab(/Comigo/))
    expect(await screen.findByText('Você não tem conversas em atendimento.')).toBeInTheDocument()
    await user.click(tab(/Resolvidas/))
    expect(await screen.findByText('Nenhuma conversa resolvida ainda.')).toBeInTheDocument()
  })

  it('shows an error with a retry button when the list fails', async () => {
    const api = fakeInboxApi([anaComplaint()])
    api.failWith = 503
    const { user } = renderApp('/app/joes-pizza/inbox')

    expect(await screen.findByRole('alert')).toHaveTextContent('Algo deu errado. Tente novamente.')
    api.failWith = null
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }))
    expect(await (await conversationList()).findByRole('link', { name: /#AAAA/ })).toBeInTheDocument()
  })

  it('loads the next page with the cursor', async () => {
    const api = fakeInboxApi([
      anaComplaint(),
      conversation(BRUNO, 'WAITING', [message('CUSTOMER', 'Oi')]),
      conversation(CARLA, 'WAITING', [message('CUSTOMER', 'Olá')]),
    ])
    api.pageSize = 2
    const { user } = renderApp('/app/joes-pizza/inbox')

    await (await conversationList()).findByRole('link', { name: /#BBBB/ })
    expect((await conversationList()).queryByRole('link', { name: /#CCCC/ })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Carregar mais' }))

    expect(await (await conversationList()).findByRole('link', { name: /#CCCC/ })).toBeInTheDocument()
    expect(api.requests).toContain(`${BASE}?state=WAITING&cursor=2`)
    expect(screen.queryByRole('button', { name: 'Carregar mais' })).not.toBeInTheDocument()
  })
})

describe('conversation', () => {
  it('shows the transcript with a style per sender and the context panel', async () => {
    fakeInboxApi([anaComplaint()])
    const { user } = renderApp('/app/joes-pizza/inbox')

    await user.click(await (await conversationList()).findByRole('link', { name: /#AAAA/ }))

    expect(await (await transcript()).findByRole('heading', { name: 'Cliente anônimo #AAAA' })).toBeInTheDocument()
    expect((await transcript()).getByText(/Site · PT · Esperando a equipe · esperando há 04:1\d/)).toBeInTheDocument()
    const messages = (await transcript()).getAllByRole('listitem')
    expect(messages.map((item) => item.dataset.kind)).toEqual(['customer', 'ai', 'system', 'internal', 'human'])
    expect(messages[0]).toHaveClass('inbox-message--customer')
    expect(messages[0]).toHaveTextContent('Cliente ·')
    expect(messages[1]).toHaveTextContent('IA ·')
    expect(messages[2]).toHaveTextContent('Conversa passada para a equipe: reclamação')
    expect(messages[3]).toHaveClass('inbox-message--internal')
    expect(messages[3]).toHaveTextContent('Nota interna · Maria')
    expect(messages[4]).toHaveTextContent('Maria (equipe)')
    expect(screen.getByTestId('reply-slot')).toBeInTheDocument()
    expect((await conversationList()).getByRole('link', { name: /#AAAA/ })).toHaveAttribute('aria-current', 'true')

    expect((await context()).getByText('reclamação')).toBeInTheDocument()
    expect((await context()).getByText('Cliente recebeu o pedido errado.')).toBeInTheDocument()
    expect((await context()).getByText('complaint')).toBeInTheDocument()
    expect((await context()).getByText('DC-2003')).toBeInTheDocument()
    expect((await context()).getByText('Políticas › Trocas')).toBeInTheDocument()
  })

  it('opens the conversation in the URL and shows the empty context', async () => {
    fakeInboxApi([conversation(BRUNO, 'AI', [message('CUSTOMER', 'Qual o horário?')])])
    renderApp(`/app/joes-pizza/inbox?tab=ai&c=${BRUNO}`)

    expect(await (await transcript()).findByText('Qual o horário?')).toBeInTheDocument()
    expect((await context()).getByText('Esta conversa não foi passada para a equipe.')).toBeInTheDocument()
    expect((await context()).getByText('Nenhum pedido citado.')).toBeInTheDocument()
    expect((await context()).getByText('A IA não usou documentos nesta conversa.')).toBeInTheDocument()
  })

  it('shows a not-found error for an unknown conversation', async () => {
    fakeInboxApi([])
    renderApp('/app/joes-pizza/inbox?c=nope')

    expect(await (await transcript()).findByRole('alert')).toHaveTextContent('Conversa não encontrada.')
  })
})

describe('live updates', () => {
  it('refreshes the open conversation and the list on message.created', async () => {
    const api = fakeInboxApi([anaComplaint()])
    renderApp(`/app/joes-pizza/inbox?c=${ANA}`)
    await (await transcript()).findByText('Oi! Já estou vendo seu pedido.')
    await waitFor(() => expect(api.streams).toHaveLength(1))

    const stored = message('CUSTOMER', 'Obrigado, aguardo!')
    api.conversations[0].messages.push(stored)
    api.streams[0].send('message.created', { conversationId: ANA, id: stored.id }, stored.id)

    expect(await (await transcript()).findByText('Obrigado, aguardo!')).toBeInTheDocument()
    await waitFor(async () =>
      expect((await conversationList()).getByRole('link', { name: /#AAAA/ })).toHaveTextContent('Obrigado, aguardo!'),
    )
  })

  it('moves a conversation between tabs on conversation.state_changed', async () => {
    const api = fakeInboxApi([conversation(BRUNO, 'AI', [message('CUSTOMER', 'Quero falar com alguém')])])
    renderApp('/app/joes-pizza/inbox')
    expect(await screen.findByText('Ninguém está esperando por você agora.')).toBeInTheDocument()
    await waitFor(() => expect(api.streams).toHaveLength(1))
    expect(tab(/IA atendendo/)).toHaveTextContent('IA atendendo(1)')

    // A new handoff: the AI conversation now waits for staff.
    api.conversations[0] = { ...conversation(BRUNO, 'WAITING', api.conversations[0].messages) }
    api.streams[0].send('conversation.state_changed', { conversationId: BRUNO, state: 'WAITING' })

    expect(await (await conversationList()).findByRole('link', { name: /#BBBB/ })).toBeInTheDocument()
    expect(tab(/Precisa de você/)).toHaveTextContent('Precisa de você(1)')
    expect(tab(/IA atendendo/)).toHaveTextContent('IA atendendo(0)')
  })

  it('reconnects with Last-Event-ID and refetches after the stream drops', async () => {
    const api = fakeInboxApi([anaComplaint()])
    renderApp('/app/joes-pizza/inbox')
    await (await conversationList()).findByRole('link', { name: /#AAAA/ })
    await waitFor(() => expect(api.streams).toHaveLength(1))
    api.streams[0].send('ai.typing', { conversationId: ANA }, 120)
    const before = api.requests.length

    // Missed while disconnected: only a refetch can show it.
    api.conversations.push(conversation(BRUNO, 'WAITING', [message('CUSTOMER', 'Oi')]))
    api.streams[0].close()

    await waitFor(() => expect(api.streams).toHaveLength(2))
    expect(api.streams[1].headers.get('Last-Event-ID')).toBe('120')
    expect(await (await conversationList()).findByRole('link', { name: /#BBBB/ })).toBeInTheDocument()
    expect(api.requests.length).toBeGreaterThan(before)
  })
})
