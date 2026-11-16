import { screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import type { DocumentDetail, DocumentStatus } from '../api/knowledge'
import { JOES_AGENT, JOES_OWNER, fakeBackend, makeUser, renderApp } from '../test/renderApp'
import { POLL_INTERVAL_MS } from './queries'

const BASE = '/api/v1/panel/joes-pizza/documents'
const PLAYGROUND = '/api/v1/panel/joes-pizza/playground'

function makeDocument(id: number, title: string, status: DocumentStatus, content = `# ${title}`): DocumentDetail {
  return {
    id,
    sourcePath: `joes-pizza/doc-${id}.md`,
    title,
    source: id === 1 ? 'FILE' : 'EDITOR',
    status,
    characters: content.length,
    updatedAt: '2026-11-16T14:05:00Z',
    updatedBy: null,
    content,
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

interface KnowledgeApiOptions {
  documents?: DocumentDetail[]
  // Status for every knowledge request (e.g. 503), instead of answering.
  failWith?: number
}

// The auth API of fakeBackend plus an in-memory knowledge API. A saved
// document is PENDING; the next GET of it finds it INDEXED, as if the job
// worker had run in between.
function fakeKnowledgeApi(memberships = [JOES_OWNER], options: KnowledgeApiOptions = {}): Mock {
  const authFetch = fakeBackend({ loggedIn: makeUser(memberships) })
  let documents = [...(options.documents ?? [])]
  let nextId = 100

  const fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
    const method = init.method ?? 'GET'
    if (!url.startsWith('/api/v1/panel/joes-pizza/')) {
      return authFetch(url, init)
    }
    if (options.failWith) {
      return json({ detail: 'down' }, options.failWith)
    }
    if (url === PLAYGROUND && method === 'POST') {
      const body = JSON.parse(init.body as string)
      return json({
        conversationId: body.conversationId ?? 'conv-1',
        reply: 'Abrimos de terça a domingo, das 11h às 22h.',
        sources: [{ title: 'Perguntas frequentes', sourcePath: 'joes-pizza/faq.md', section: 'Horário' }],
        handoff: false,
      })
    }
    if (url === BASE && method === 'GET') {
      return json(documents.map((document) => ({ ...document, content: undefined })))
    }
    if (url === BASE && method === 'POST') {
      const body = JSON.parse(init.body as string)
      const created = makeDocument(nextId++, body.title, 'PENDING', body.content)
      documents.push(created)
      return json(created, 201)
    }
    if (url === `${BASE}/reindex` && method === 'POST') {
      documents = documents.map((document) => ({ ...document, status: 'PENDING' }))
      return json({ queued: documents.length }, 202)
    }
    const id = Number(url.slice(BASE.length + 1))
    const index = documents.findIndex((document) => document.id === id)
    if (index === -1) {
      return json({ detail: 'Document not found' }, 404)
    }
    if (method === 'GET') {
      const found = documents[index]
      documents[index] = { ...found, status: 'INDEXED' }
      return json(found)
    }
    if (method === 'PUT') {
      const body = JSON.parse(init.body as string)
      documents[index] = { ...documents[index], ...body, status: 'PENDING' }
      return json(documents[index])
    }
    if (method === 'DELETE') {
      documents.splice(index, 1)
      return new Response(null, { status: 204 })
    }
    throw new Error(`Unexpected request in test: ${method} ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function requests(fetchMock: Mock, method: string, url: string) {
  return fetchMock.mock.calls.filter(
    ([callUrl, init]) => callUrl === url && ((init as RequestInit | undefined)?.method ?? 'GET') === method,
  )
}

const SEEDED = [
  makeDocument(1, 'Perguntas frequentes', 'INDEXED'),
  makeDocument(2, 'Políticas', 'FAILED'),
  makeDocument(3, 'Cardápio', 'INDEXED'),
]

beforeEach(() => {
  document.cookie = 'tendwise_csrf=csrf-value'
})

describe('document list', () => {
  it('shows each document with its source and status', async () => {
    fakeKnowledgeApi([JOES_OWNER], {
      documents: [SEEDED[0], SEEDED[1], makeDocument(3, 'Cardápio', 'PENDING')],
    })
    renderApp('/app/joes-pizza/knowledge')

    const table = await screen.findByRole('table')
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringContaining('Perguntas frequentesArquivo inicialIndexado'),
      expect.stringContaining('PolíticasEditorFalhou'),
      expect.stringContaining('CardápioEditorIndexando'),
    ])
    expect(rows[0]).toHaveTextContent('16/11/2026')
  })

  it('shows a friendly message when the API fails', async () => {
    fakeKnowledgeApi([JOES_OWNER], { failWith: 503 })
    renderApp('/app/joes-pizza/knowledge')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'O serviço está indisponível agora. Tente novamente em instantes.',
    )
    expect(screen.getByRole('button', { name: 'Tentar novamente' })).toBeInTheDocument()
  })

  it('lets the owner reindex every document', async () => {
    const fetchMock = fakeKnowledgeApi([JOES_OWNER], { documents: SEEDED })
    const { user } = renderApp('/app/joes-pizza/knowledge')

    await user.click(await screen.findByRole('button', { name: 'Reindexar' }))

    expect(await screen.findByText('3 documentos enviados para reindexação.')).toBeInTheDocument()
    const [reindex] = requests(fetchMock, 'POST', `${BASE}/reindex`)
    expect(new Headers((reindex[1] as RequestInit).headers).get('X-CSRF-Token')).toBe('csrf-value')
  })
})

describe('editing', () => {
  it('lets the owner save a new document and shows PENDING, then INDEXED', async () => {
    const fetchMock = fakeKnowledgeApi([JOES_OWNER], { documents: SEEDED })
    const { user } = renderApp('/app/joes-pizza/knowledge')

    await user.click(await screen.findByRole('link', { name: 'Novo documento' }))
    await user.type(screen.getByLabelText('Título'), 'Estacionamento')
    await user.type(screen.getByLabelText('Conteúdo (Markdown)'), '# Estacionamento{enter}{enter}Grátis para **clientes**.')

    // The preview renders the markdown as it is typed.
    const preview = screen.getByRole('region', { name: 'Pré-visualização' })
    expect(within(preview).getByRole('heading', { name: 'Estacionamento' })).toBeInTheDocument()
    expect(within(preview).getByText('clientes').tagName).toBe('STRONG')

    await user.click(screen.getByRole('button', { name: 'Salvar' }))

    expect(await screen.findByText('Indexando')).toBeInTheDocument()
    expect(screen.getByTestId('current-path')).toHaveTextContent('/app/joes-pizza/knowledge/100')
    const [create] = requests(fetchMock, 'POST', BASE)
    expect(JSON.parse((create[1] as RequestInit).body as string)).toEqual({
      title: 'Estacionamento',
      content: '# Estacionamento\n\nGrátis para **clientes**.',
    })
    // Polled until the job worker has indexed it.
    expect(await screen.findByText('Indexado', {}, { timeout: POLL_INTERVAL_MS + 2000 })).toBeInTheDocument()
  })

  it('lets the owner edit an existing document', async () => {
    const fetchMock = fakeKnowledgeApi([JOES_OWNER], { documents: SEEDED })
    const { user } = renderApp('/app/joes-pizza/knowledge/2')

    const title = await screen.findByLabelText('Título')
    expect(title).toHaveValue('Políticas')
    await user.clear(title)
    await user.type(title, 'Políticas de troca')
    await user.click(screen.getByRole('button', { name: 'Salvar' }))

    expect(await screen.findByText('Documento salvo.')).toBeInTheDocument()
    expect(screen.getByText('Indexando')).toBeInTheDocument()
    const [update] = requests(fetchMock, 'PUT', `${BASE}/2`)
    expect(JSON.parse((update[1] as RequestInit).body as string).title).toBe('Políticas de troca')
  })

  it('asks for confirmation before deleting', async () => {
    const fetchMock = fakeKnowledgeApi([JOES_OWNER], { documents: SEEDED })
    const { user } = renderApp('/app/joes-pizza/knowledge/1')

    await user.click(await screen.findByRole('button', { name: 'Excluir' }))
    const dialog = screen.getByRole('alertdialog')
    expect(dialog).toHaveTextContent('Excluir este documento?')
    // A seed file comes back on restart, and the owner is told so.
    expect(dialog).toHaveTextContent('volta quando o serviço reiniciar')
    await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }))
    expect(requests(fetchMock, 'DELETE', `${BASE}/1`)).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Excluir' }))
    await user.click(screen.getByRole('button', { name: 'Sim, excluir' }))

    await waitFor(() =>
      expect(screen.getByTestId('current-path')).toHaveTextContent(/^\/app\/joes-pizza\/knowledge$/),
    )
    expect(requests(fetchMock, 'DELETE', `${BASE}/1`)).toHaveLength(1)
    const table = await screen.findByRole('table')
    expect(within(table).queryByText('Perguntas frequentes')).not.toBeInTheDocument()
  })

  it('shows a friendly message when saving fails', async () => {
    const fetchMock = fakeKnowledgeApi([JOES_OWNER], { documents: SEEDED })
    const { user } = renderApp('/app/joes-pizza/knowledge/new')
    fetchMock.mockImplementationOnce(async () => json({ detail: 'too long' }, 422))

    await user.type(await screen.findByLabelText('Título'), 'Enorme')
    await user.type(screen.getByLabelText('Conteúdo (Markdown)'), 'texto')
    await user.click(screen.getByRole('button', { name: 'Salvar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'O documento passou do limite de tamanho da base de conhecimento.',
    )
  })
})

describe('agent', () => {
  it('sees the documents without any edit control or playground', async () => {
    fakeKnowledgeApi([JOES_AGENT], { documents: SEEDED })
    const { user } = renderApp('/app/joes-pizza/knowledge')

    await screen.findByRole('table')
    expect(screen.queryByRole('link', { name: 'Novo documento' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reindexar' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Testar o assistente' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('link', { name: 'Cardápio' }))

    // The title, then the document's own "# Cardápio", rendered.
    expect(await screen.findAllByRole('heading', { name: 'Cardápio' })).toHaveLength(2)
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Salvar' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Excluir' })).not.toBeInTheDocument()
  })

  it('cannot open the new document page', async () => {
    fakeKnowledgeApi([JOES_AGENT], { documents: SEEDED })
    renderApp('/app/joes-pizza/knowledge/new')

    await screen.findByRole('table')
    expect(screen.getByTestId('current-path')).toHaveTextContent(/^\/app\/joes-pizza\/knowledge$/)
    expect(screen.queryByLabelText('Título')).not.toBeInTheDocument()
  })
})

describe('playground', () => {
  it('shows the answer and the sources it used, in one conversation', async () => {
    const fetchMock = fakeKnowledgeApi([JOES_OWNER], { documents: SEEDED })
    const { user } = renderApp('/app/joes-pizza/knowledge')

    const playground = await screen.findByRole('complementary', { name: 'Testar o assistente' })
    await user.type(within(playground).getByLabelText('Pergunta'), 'Que horas vocês abrem?')
    await user.click(within(playground).getByRole('button', { name: 'Perguntar' }))

    expect(await within(playground).findByText(/Abrimos de terça a domingo/)).toBeInTheDocument()
    expect(within(playground).getByText('Perguntas frequentes')).toBeInTheDocument()
    expect(within(playground).getByText(/Horário/)).toBeInTheDocument()

    await user.type(within(playground).getByLabelText('Pergunta'), 'E no domingo?')
    await user.click(within(playground).getByRole('button', { name: 'Perguntar' }))
    await waitFor(() => expect(requests(fetchMock, 'POST', PLAYGROUND)).toHaveLength(2))

    const bodies = requests(fetchMock, 'POST', PLAYGROUND).map(([, init]) =>
      JSON.parse((init as RequestInit).body as string),
    )
    expect(bodies).toEqual([
      { conversationId: null, message: 'Que horas vocês abrem?' },
      { conversationId: 'conv-1', message: 'E no domingo?' },
    ])
  })

  it('shows a friendly message when the assistant is down', async () => {
    const fetchMock = fakeKnowledgeApi([JOES_OWNER], { documents: SEEDED })
    const { user } = renderApp('/app/joes-pizza/knowledge')
    const playground = await screen.findByRole('complementary', { name: 'Testar o assistente' })
    fetchMock.mockImplementationOnce(async () => json({ detail: 'Assistant temporarily unavailable' }, 503))

    await user.type(within(playground).getByLabelText('Pergunta'), 'Oi')
    await user.click(within(playground).getByRole('button', { name: 'Perguntar' }))

    expect(await within(playground).findByRole('alert')).toHaveTextContent(
      'O serviço está indisponível agora.',
    )
  })
})
