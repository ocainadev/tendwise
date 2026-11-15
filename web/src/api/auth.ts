// Client for business-service's auth routes (docs/architecture.md, Auth API).

import { apiFetch, apiJson } from './http'

export type PlatformRole = 'ADMIN'
export type MembershipRole = 'OWNER' | 'AGENT'

// One business the user can open in the panel, and their role there.
export interface Membership {
  slug: string
  name: string
  role: MembershipRole
}

// Body of GET /auth/me (and of a successful login).
export interface Me {
  user: { id: number; email: string; name: string }
  // ADMIN for the platform operator, null for business staff.
  platformRole: PlatformRole | null
  memberships: Membership[]
}

// The logged-in user, or null when there is no valid session (even after
// the automatic refresh in apiFetch).
export async function fetchMe(): Promise<Me | null> {
  const response = await apiFetch('/auth/me')
  if (response.status === 401) {
    return null
  }
  if (!response.ok) {
    throw new Error(`GET /auth/me failed with HTTP ${response.status}`)
  }
  return (await response.json()) as Me
}

// Sets the session cookies. A wrong email or password, or a locked account,
// all throw ApiError with status 401.
export function login(email: string, password: string): Promise<Me> {
  return apiJson<Me>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  })
}

// Revokes the refresh token and clears the cookies. Errors are ignored: the
// web app forgets the user either way.
export async function logout(): Promise<void> {
  try {
    await apiFetch('/auth/logout', { method: 'POST' })
  } catch {
    // Server unreachable: nothing else to do.
  }
}
