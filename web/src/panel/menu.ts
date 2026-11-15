import type { MembershipRole } from '../api/auth'

// The business panel areas, in menu order. `roles` follows the Panel API
// table in docs/architecture.md: AGENT works the inbox and reads knowledge
// and the catalog; everything else is OWNER only. Each area is a placeholder
// until its task lands (inbox T45, knowledge T50/T51, catalog T52, orders T57,
// dashboard T60, settings T53, team T34).

export interface PanelArea {
  path: string
  // Key under panel.menu in the locale files.
  labelKey: string
  roles: MembershipRole[]
}

export const PANEL_AREAS: PanelArea[] = [
  { path: 'inbox', labelKey: 'panel.menu.inbox', roles: ['OWNER', 'AGENT'] },
  { path: 'knowledge', labelKey: 'panel.menu.knowledge', roles: ['OWNER', 'AGENT'] },
  { path: 'catalog', labelKey: 'panel.menu.catalog', roles: ['OWNER', 'AGENT'] },
  { path: 'orders', labelKey: 'panel.menu.orders', roles: ['OWNER'] },
  { path: 'dashboard', labelKey: 'panel.menu.dashboard', roles: ['OWNER'] },
  { path: 'settings', labelKey: 'panel.menu.settings', roles: ['OWNER'] },
  { path: 'team', labelKey: 'panel.menu.team', roles: ['OWNER'] },
]

// The area a user lands on when they open a business.
export const DEFAULT_PANEL_AREA = 'inbox'
