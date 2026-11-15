import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import {
  CORRECT_PASSWORD,
  DENIM_AGENT,
  JOES_AGENT,
  JOES_OWNER,
  fakeBackend,
  makeUser,
  renderApp,
} from './test/renderApp'

const ALL_MENU_ITEMS = [
  'Caixa de entrada',
  'Conhecimento',
  'Catálogo',
  'Pedidos',
  'Relatórios',
  'Configurações',
  'Equipe',
]
const OWNER_ONLY_ITEMS = ['Pedidos', 'Relatórios', 'Configurações', 'Equipe']

// Waits for redirects to settle on the given URL path.
async function expectPath(path: string) {
  await waitFor(() => expect(screen.getByTestId('current-path')).toHaveTextContent(path))
}

function menuItems(): string[] {
  const nav = screen.getByRole('navigation', { name: 'Menu da empresa' })
  return within(nav)
    .getAllByRole('link')
    .map((link) => link.textContent ?? '')
}

describe('demo', () => {
  it('renders the chat at /demo', async () => {
    fakeBackend({ loggedIn: null })
    renderApp('/demo')

    expect(screen.getByLabelText('Message')).toBeInTheDocument()
    expect(screen.getByLabelText('Business')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send' })).toBeInTheDocument()
  })

  it('redirects / to the demo', async () => {
    fakeBackend({ loggedIn: null })
    renderApp('/')

    await expectPath('/demo')
    expect(screen.getByLabelText('Message')).toBeInTheDocument()
  })
})

describe('login', () => {
  it('sends an unauthenticated user to login and back to the page after login', async () => {
    const fetchMock = fakeBackend({ loggedIn: null, loginAs: makeUser([JOES_OWNER]) })
    const { user } = renderApp('/app/joes-pizza/team')

    expect(await screen.findByRole('heading', { name: 'Entrar no Tendwise' })).toBeInTheDocument()
    await expectPath('/login')

    await user.type(screen.getByLabelText('E-mail'), 'ana@example.test')
    await user.type(screen.getByLabelText('Senha'), CORRECT_PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Entrar' }))

    expect(await screen.findByRole('heading', { name: 'Equipe' })).toBeInTheDocument()
    await expectPath('/app/joes-pizza/team')
    const loginCall = fetchMock.mock.calls.find(([url]) => url === '/auth/login')
    expect(JSON.parse(loginCall![1].body)).toEqual({
      email: 'ana@example.test',
      password: CORRECT_PASSWORD,
    })
  })

  it('shows one generic error for a wrong password', async () => {
    fakeBackend({ loggedIn: null, loginAs: makeUser([JOES_OWNER]) })
    const { user } = renderApp('/login')

    await user.type(await screen.findByLabelText('E-mail'), 'ana@example.test')
    await user.type(screen.getByLabelText('Senha'), 'wrong-password')
    await user.click(screen.getByRole('button', { name: 'Entrar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('E-mail ou senha incorretos.')
    await expectPath('/login')
  })

  it('sends a user who opened /login directly to their business', async () => {
    fakeBackend({ loggedIn: null, loginAs: makeUser([JOES_AGENT]) })
    const { user } = renderApp('/login')

    await user.type(await screen.findByLabelText('E-mail'), 'ana@example.test')
    await user.type(screen.getByLabelText('Senha'), CORRECT_PASSWORD)
    await user.click(screen.getByRole('button', { name: 'Entrar' }))

    await expectPath('/app/joes-pizza/inbox')
  })

  it('logs out and goes back to the login page', async () => {
    fakeBackend({ loggedIn: makeUser([JOES_OWNER]) })
    const { user } = renderApp('/app/joes-pizza/inbox')

    await user.click(await screen.findByRole('button', { name: 'Sair' }))

    expect(await screen.findByRole('heading', { name: 'Entrar no Tendwise' })).toBeInTheDocument()
    await expectPath('/login')
  })
})

describe('business panel', () => {
  it('shows every menu item to an OWNER', async () => {
    fakeBackend({ loggedIn: makeUser([JOES_OWNER]) })
    renderApp('/app/joes-pizza')

    expect(await screen.findByRole('heading', { name: 'Caixa de entrada' })).toBeInTheDocument()
    await expectPath('/app/joes-pizza/inbox')
    expect(menuItems()).toEqual(ALL_MENU_ITEMS)
    expect(screen.getByText('Em breve')).toBeInTheDocument()
  })

  it('hides OWNER-only menu items from an AGENT', async () => {
    fakeBackend({ loggedIn: makeUser([JOES_AGENT]) })
    renderApp('/app/joes-pizza')

    expect(await screen.findByRole('heading', { name: 'Caixa de entrada' })).toBeInTheDocument()
    expect(menuItems()).toEqual(['Caixa de entrada', 'Conhecimento', 'Catálogo'])
    for (const item of OWNER_ONLY_ITEMS) {
      expect(screen.queryByRole('link', { name: item })).not.toBeInTheDocument()
    }
  })

  it('sends an AGENT who types an OWNER-only URL to the inbox', async () => {
    fakeBackend({ loggedIn: makeUser([JOES_AGENT]) })
    renderApp('/app/joes-pizza/settings')

    expect(await screen.findByRole('heading', { name: 'Caixa de entrada' })).toBeInTheDocument()
    await expectPath('/app/joes-pizza/inbox')
  })

  it('switches between the businesses of a user with several memberships', async () => {
    fakeBackend({ loggedIn: makeUser([JOES_OWNER, DENIM_AGENT]) })
    const { user } = renderApp('/app/joes-pizza/team')

    expect(await screen.findByRole('heading', { name: 'Equipe' })).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Empresa'), 'Denim Corner')

    await expectPath('/app/denim-corner/inbox')
    expect(screen.getByText('Atendente')).toBeInTheDocument()
    expect(menuItems()).toEqual(['Caixa de entrada', 'Conhecimento', 'Catálogo'])
  })

  it('lists the businesses at /app for a user with several memberships', async () => {
    fakeBackend({ loggedIn: makeUser([JOES_OWNER, DENIM_AGENT]) })
    const { user } = renderApp('/app')

    expect(await screen.findByRole('heading', { name: 'Escolha uma empresa' })).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: /Denim Corner/ }))

    await expectPath('/app/denim-corner/inbox')
  })

  it('sends a user away from a business they do not belong to', async () => {
    fakeBackend({ loggedIn: makeUser([JOES_OWNER]) })
    renderApp('/app/denim-corner/inbox')

    // Not a member: the chooser, which goes on to the user's only business.
    await expectPath('/app/joes-pizza/inbox')
  })
})

describe('admin panel', () => {
  it('does not open /admin for a user who is not a platform admin', async () => {
    fakeBackend({ loggedIn: makeUser([JOES_OWNER]) })
    renderApp('/admin')

    expect(await screen.findByRole('heading', { name: 'Caixa de entrada' })).toBeInTheDocument()
    await expectPath('/app/joes-pizza/inbox')
    expect(screen.queryByText('Administração')).not.toBeInTheDocument()
  })

  it('opens /admin for a platform admin', async () => {
    fakeBackend({ loggedIn: makeUser([], 'ADMIN') })
    renderApp('/admin')

    expect(await screen.findByRole('heading', { name: 'Visão geral' })).toBeInTheDocument()
    await expectPath('/admin/overview')
    expect(screen.getByRole('navigation', { name: 'Menu da administração' })).toBeInTheDocument()
  })
})
