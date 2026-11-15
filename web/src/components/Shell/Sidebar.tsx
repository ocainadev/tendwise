import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { NavLink } from 'react-router'
import { useCurrentUser, useLogout } from '../../auth/session'
import './Shell.css'

export interface SidebarItem {
  to: string
  label: string
}

interface SidebarProps {
  // Shown under the logo: the business switcher, or the admin title.
  header: ReactNode
  navLabel: string
  items: SidebarItem[]
}

// The left column shared by the business panel and the admin panel: logo,
// header, menu links and, at the bottom, the user and the logout button.
export function Sidebar({ header, navLabel, items }: SidebarProps) {
  const { t } = useTranslation()
  const me = useCurrentUser()
  const logout = useLogout()

  return (
    <aside className="shell-sidebar">
      <p className="shell-sidebar__logo">{t('common.appName')}</p>
      <div className="shell-sidebar__header">{header}</div>
      <nav aria-label={navLabel}>
        <ul className="shell-sidebar__menu">
          {items.map((item) => (
            <li key={item.to}>
              <NavLink to={item.to} className="shell-sidebar__link">
                {item.label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
      <div className="shell-sidebar__footer">
        <span className="shell-sidebar__user">{me.user.name}</span>
        <button
          type="button"
          className="shell-button shell-button--secondary"
          onClick={() => logout.mutate()}
          disabled={logout.isPending}
        >
          {t('common.logout')}
        </button>
      </div>
    </aside>
  )
}
