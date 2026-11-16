// Client for the team page and the invitation link (T34): members, pending
// invitations and the public POST /auth/invitations/accept.

import type { MembershipRole } from './auth'
import { apiFetch, apiJson, throwIfNotOk } from './http'

export interface Member {
  userId: number
  name: string
  email: string
  role: MembershipRole
  joinedAt: string
}

export interface Invitation {
  id: number
  email: string
  role: MembershipRole
  expiresAt: string
}

export interface AcceptedInvitation {
  slug: string
  businessName: string
  role: MembershipRole
  accountCreated: boolean
}

// Problem titles the pages react to.
export const LAST_OWNER = 'Last owner'
export const ALREADY_MEMBER = 'Already a member'
export const ACCOUNT_DETAILS_REQUIRED = 'Account details required'

function panelPath(slug: string, rest: string): string {
  return `/api/v1/panel/${encodeURIComponent(slug)}/${rest}`
}

export function listMembers(slug: string): Promise<Member[]> {
  return apiJson<Member[]>(panelPath(slug, 'team'))
}

// 409 (LAST_OWNER) when the business would have no owner left.
export function changeRole(slug: string, userId: number, role: MembershipRole): Promise<Member> {
  return apiJson<Member>(panelPath(slug, `team/${userId}`), {
    method: 'PATCH',
    body: JSON.stringify({ role }),
  })
}

export async function removeMember(slug: string, userId: number): Promise<void> {
  await throwIfNotOk(await apiFetch(panelPath(slug, `team/${userId}`), { method: 'DELETE' }))
}

export function listInvitations(slug: string): Promise<Invitation[]> {
  return apiJson<Invitation[]>(panelPath(slug, 'invitations'))
}

// 409 (ALREADY_MEMBER) when the email is already in the team.
export function createInvitation(slug: string, email: string, role: MembershipRole): Promise<Invitation> {
  return apiJson<Invitation>(panelPath(slug, 'invitations'), {
    method: 'POST',
    body: JSON.stringify({ email, role }),
  })
}

export async function revokeInvitation(slug: string, id: number): Promise<void> {
  await throwIfNotOk(await apiFetch(panelPath(slug, `invitations/${id}`), { method: 'DELETE' }))
}

// Public: no session needed. name and password are only read when no account
// exists for the invited email (else 400 ACCOUNT_DETAILS_REQUIRED). Any bad
// token answers 400 "Invalid invitation".
export function acceptInvitation(
  token: string,
  name: string | null,
  password: string | null,
): Promise<AcceptedInvitation> {
  return apiJson<AcceptedInvitation>('/auth/invitations/accept', {
    method: 'POST',
    body: JSON.stringify({ token, name, password }),
  })
}
