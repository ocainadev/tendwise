import { screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import type { Invitation, Member } from '../api/team'
import { JOES_OWNER, fakeBackend, makeUser, renderApp } from '../test/renderApp'

const BASE = '/api/v1/panel/joes-pizza'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const LAST_OWNER = {
  status: 409,
  title: 'Last owner',
  detail: 'A business needs at least one owner. Make someone else an owner first.',
}

// fakeBackend's auth API plus an in-memory team API that, like
// business-service, refuses to leave the business without an OWNER.
function fakeTeamApi(): Mock {
  // The logged-in user (id 1, from makeUser) is the only owner.
  const authFetch = fakeBackend({ loggedIn: makeUser([JOES_OWNER]) })
  let members: Member[] = [
    { userId: 1, name: 'Ana Souza', email: 'ana@example.test', role: 'OWNER', joinedAt: '2026-11-01T10:00:00Z' },
    { userId: 2, name: 'Bruno Lima', email: 'bruno@example.test', role: 'AGENT', joinedAt: '2026-11-02T10:00:00Z' },
  ]
  let invitations: Invitation[] = [
    { id: 7, email: 'carla@example.test', role: 'AGENT', expiresAt: '2026-11-20T10:00:00Z' },
  ]
  let nextId = 8

  function ownersAfter(changed: Member[]) {
    return changed.filter((member) => member.role === 'OWNER').length
  }

  const fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
    const method = init.method ?? 'GET'
    const body = init.body ? JSON.parse(init.body as string) : null
    if (url === `${BASE}/team` && method === 'GET') return json(members)
    if (url === `${BASE}/invitations` && method === 'GET') return json(invitations)
    if (url === `${BASE}/invitations` && method === 'POST') {
      if (members.some((member) => member.email === body.email)) {
        return json({ status: 409, title: 'Already a member' }, 409)
      }
      const created = { id: nextId++, email: body.email, role: body.role, expiresAt: '2026-11-23T10:00:00Z' }
      invitations = [...invitations, created]
      return json(created, 201)
    }
    const invitationMatch = url.match(/\/invitations\/(\d+)$/)
    if (invitationMatch && method === 'DELETE') {
      invitations = invitations.filter((invitation) => invitation.id !== Number(invitationMatch[1]))
      return new Response(null, { status: 204 })
    }
    const memberMatch = url.match(/\/team\/(\d+)$/)
    if (memberMatch) {
      const userId = Number(memberMatch[1])
      if (method === 'PATCH') {
        const changed = members.map((member) => (member.userId === userId ? { ...member, role: body.role } : member))
        if (ownersAfter(changed) === 0) return json(LAST_OWNER, 409)
        members = changed
        return json(members.find((member) => member.userId === userId))
      }
      if (method === 'DELETE') {
        const changed = members.filter((member) => member.userId !== userId)
        if (ownersAfter(changed) === 0) return json(LAST_OWNER, 409)
        members = changed
        return new Response(null, { status: 204 })
      }
    }
    return authFetch(url, init)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function requests(fetchMock: Mock, method: string, url: string) {
  return fetchMock.mock.calls.filter(([callUrl, init]) => callUrl === url && (init?.method ?? 'GET') === method)
}

function memberRow(name: string): HTMLElement {
  return screen.getByRole('cell', { name: new RegExp(name) }).closest('tr') as HTMLElement
}

beforeEach(() => {
  document.cookie = 'tendwise_csrf=csrf-value'
})

describe('team page', () => {
  it('lists the members and the pending invitations', async () => {
    fakeTeamApi()
    renderApp('/app/joes-pizza/team')

    expect(await screen.findByText('bruno@example.test')).toBeInTheDocument()
    expect(within(memberRow('Ana Souza')).getByText('(você)')).toBeInTheDocument()
    expect(screen.getByLabelText('Papel de Bruno Lima')).toHaveValue('AGENT')
    expect(await screen.findByText('carla@example.test')).toBeInTheDocument()
  })

  it('changes a role', async () => {
    const fetchMock = fakeTeamApi()
    const { user } = renderApp('/app/joes-pizza/team')

    await user.selectOptions(await screen.findByLabelText('Papel de Bruno Lima'), 'OWNER')

    expect(await screen.findByText('Papel de Bruno Lima alterado.')).toBeInTheDocument()
    const [[, init]] = requests(fetchMock, 'PATCH', `${BASE}/team/2`)
    expect(JSON.parse(init.body)).toEqual({ role: 'OWNER' })
    await waitFor(() => expect(screen.getByLabelText('Papel de Bruno Lima')).toHaveValue('OWNER'))
  })

  it('shows the last-owner error and keeps the role', async () => {
    fakeTeamApi()
    const { user } = renderApp('/app/joes-pizza/team')

    await user.selectOptions(await screen.findByLabelText('Papel de Ana Souza'), 'AGENT')

    expect(await screen.findByRole('alert')).toHaveTextContent('A empresa precisa de pelo menos um proprietário')
    expect(screen.getByLabelText('Papel de Ana Souza')).toHaveValue('OWNER')
  })

  it('removes a member only after confirmation', async () => {
    const fetchMock = fakeTeamApi()
    const { user } = renderApp('/app/joes-pizza/team')

    await screen.findByText('bruno@example.test')
    await user.click(within(memberRow('Bruno Lima')).getByRole('button', { name: 'Remover' }))
    expect(screen.getByText(/Remover Bruno Lima da equipe\?/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(requests(fetchMock, 'DELETE', `${BASE}/team/2`)).toHaveLength(0)

    await user.click(within(memberRow('Bruno Lima')).getByRole('button', { name: 'Remover' }))
    await user.click(screen.getByRole('button', { name: 'Sim, remover' }))

    await waitFor(() => expect(screen.queryByText('bruno@example.test')).not.toBeInTheDocument())
    expect(requests(fetchMock, 'DELETE', `${BASE}/team/2`)).toHaveLength(1)
  })

  it('shows the last-owner error when removing the only owner', async () => {
    fakeTeamApi()
    const { user } = renderApp('/app/joes-pizza/team')

    await screen.findByText('ana@example.test')
    await user.click(within(memberRow('Ana Souza')).getByRole('button', { name: 'Remover' }))
    await user.click(screen.getByRole('button', { name: 'Sim, remover' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('A empresa precisa de pelo menos um proprietário')
    expect(screen.getByText('ana@example.test')).toBeInTheDocument()
  })

  it('invites by email and role, and revokes an invitation', async () => {
    const fetchMock = fakeTeamApi()
    const { user } = renderApp('/app/joes-pizza/team')

    const form = await screen.findByRole('form', { name: 'Convidar pessoa' })
    await user.type(within(form).getByLabelText('E-mail'), 'davi@example.test')
    await user.selectOptions(within(form).getByLabelText('Papel'), 'OWNER')
    await user.click(within(form).getByRole('button', { name: 'Enviar convite' }))

    expect(await screen.findByText('Convite enviado para davi@example.test.')).toBeInTheDocument()
    const [[, init]] = requests(fetchMock, 'POST', `${BASE}/invitations`)
    expect(JSON.parse(init.body)).toEqual({ email: 'davi@example.test', role: 'OWNER' })
    expect(await screen.findByRole('button', { name: 'Revogar convite de davi@example.test' })).toBeInTheDocument()
    expect(within(form).getByLabelText('E-mail')).toHaveValue('')

    await user.click(screen.getByRole('button', { name: 'Revogar convite de carla@example.test' }))
    await waitFor(() => expect(screen.queryByText('carla@example.test')).not.toBeInTheDocument())
    expect(requests(fetchMock, 'DELETE', `${BASE}/invitations/7`)).toHaveLength(1)
  })

  it('says when the invited email is already a member', async () => {
    fakeTeamApi()
    const { user } = renderApp('/app/joes-pizza/team')

    const form = await screen.findByRole('form', { name: 'Convidar pessoa' })
    await user.type(within(form).getByLabelText('E-mail'), 'bruno@example.test')
    await user.click(within(form).getByRole('button', { name: 'Enviar convite' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Esta pessoa já faz parte da equipe.')
  })
})
