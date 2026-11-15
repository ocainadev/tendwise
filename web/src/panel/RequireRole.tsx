import type { ReactNode } from 'react'
import { Navigate } from 'react-router'
import type { MembershipRole } from '../api/auth'
import { useMembership } from '../auth/session'
import { DEFAULT_PANEL_AREA } from './menu'

// Renders a panel area only for the roles that may open it. Hiding the menu
// item is not enough: the user can still type the URL. (The API checks the
// role too, from T32; this only keeps the UI consistent.)
export function RequireRole({ roles, children }: { roles: MembershipRole[]; children: ReactNode }) {
  const membership = useMembership()
  // PanelLayout already sent non-members away.
  if (membership && !roles.includes(membership.role)) {
    return <Navigate to={`/app/${membership.slug}/${DEFAULT_PANEL_AREA}`} replace />
  }
  return children
}
