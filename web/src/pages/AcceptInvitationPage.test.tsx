import { screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi, type Mock } from 'vitest'
import type { Me } from '../api/auth'
import { CORRECT_PASSWORD, JOES_OWNER, fakeBackend, makeUser, renderApp } from '../test/renderApp'

const ACCEPT_URL = '/auth/invitations/accept'
const ACCEPTED = { slug: 'joes-pizza', businessName: "Joe's Pizza", role: 'AGENT', accountCreated: true }
const JOES_AGENT_ME = makeUser([{ slug: 'joes-pizza', name: "Joe's Pizza", role: 'AGENT' }])

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

// fakeBackend's auth API plus POST /auth/invitations/accept, which answers
// `acceptAnswer()`.
function fakeAcceptApi(loggedIn: Me | null, acceptAnswer: () => Response, loginAs?: Me): Mock {
  const authFetch = fakeBackend({ loggedIn, loginAs })
  const fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
    if (url === ACCEPT_URL && init.method === 'POST') {
      return acceptAnswer()
    }
    return authFetch(url, init)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function acceptBodies(fetchMock: Mock) {
  return fetchMock.mock.calls.filter(([url]) => url === ACCEPT_URL).map(([, init]) => JSON.parse(init.body))
}

describe('accept invitation page', () => {
  it('creates the account, then logs in straight into the panel', async () => {
    const fetchMock = fakeAcceptApi(null, () => json(ACCEPTED), JOES_AGENT_ME)
    const { user } = renderApp('/invitations/accept#token=secret-token')

    await user.type(await screen.findByLabelText('Seu nome'), 'Bruno Lima')
    await user.type(screen.getByLabelText(/^Senha/), 'nova-senha-123')
    await user.click(screen.getByRole('button', { name: 'Criar conta e aceitar' }))

    await waitFor(() => expect(screen.getByTestId('current-path')).toHaveTextContent('/login'))
    expect(screen.getByText("Convite aceito! Entre para abrir Joe's Pizza.")).toBeInTheDocument()
    expect(acceptBodies(fetchMock)).toEqual([{ token: 'secret-token', name: 'Bruno Lima', password: 'nova-senha-123' }])

    await user.type(screen.getByLabelText('E-mail'), 'bruno@example.test')
    await user.type(screen.getByLabelText('Senha'), CORRECT_PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Entrar' }))
    await waitFor(() => expect(screen.getByTestId('current-path')).toHaveTextContent('/app/joes-pizza/inbox'))
  })

  it('lets an existing user accept without a name and password', async () => {
    const fetchMock = fakeAcceptApi(null, () => json({ ...ACCEPTED, accountCreated: false }))
    const { user } = renderApp('/invitations/accept#token=secret-token')

    await user.click(await screen.findByRole('button', { name: 'Aceitar com minha conta' }))

    await waitFor(() => expect(screen.getByTestId('current-path')).toHaveTextContent('/login'))
    expect(acceptBodies(fetchMock)).toEqual([{ token: 'secret-token', name: null, password: null }])
  })

  it('asks for name and password when the email has no account yet', async () => {
    fakeAcceptApi(null, () => json({ status: 400, title: 'Account details required' }, 400))
    const { user } = renderApp('/invitations/accept#token=secret-token')

    await user.click(await screen.findByRole('button', { name: 'Aceitar com minha conta' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Ainda não há conta para este e-mail.')
  })

  it('explains an expired or used invitation', async () => {
    fakeAcceptApi(null, () => json({ status: 400, title: 'Invalid invitation' }, 400))
    const { user } = renderApp('/invitations/accept#token=old-token')

    await user.click(await screen.findByRole('button', { name: 'Aceitar com minha conta' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Este convite expirou, foi revogado ou já foi usado.')
  })

  it('refreshes the session of a logged-in user and opens the new business', async () => {
    // Logged in as an owner of another business; after the refresh the fake
    // /auth/me still answers the same user, so give them Joe's already.
    const fetchMock = fakeAcceptApi(makeUser([JOES_OWNER]), () => json({ ...ACCEPTED, accountCreated: false }))
    const { user } = renderApp('/invitations/accept#token=secret-token')

    expect(await screen.findByText('Você entrou como Ana Souza.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Aceitar convite' }))

    await waitFor(() => expect(screen.getByTestId('current-path')).toHaveTextContent('/app/joes-pizza'))
    expect(fetchMock.mock.calls.some(([url, init]) => url === '/auth/refresh' && init?.method === 'POST')).toBe(true)
  })

  it('says the link is incomplete without a token', async () => {
    fakeAcceptApi(null, () => json(ACCEPTED))
    renderApp('/invitations/accept')

    expect(await screen.findByRole('alert')).toHaveTextContent('Este link de convite está incompleto.')
  })
})
