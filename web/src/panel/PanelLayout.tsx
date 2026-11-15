import { useTranslation } from 'react-i18next'
import { Navigate, Outlet, useNavigate } from 'react-router'
import { useCurrentUser, useMembership } from '../auth/session'
import { Sidebar } from '../components/Shell/Sidebar'
import { PANEL_AREAS } from './menu'

// /app/:slug/*: the business panel. Sidebar with the business switcher and the
// menu items the user's role may open; the area itself renders in <Outlet>.
export function PanelLayout() {
  const { t } = useTranslation()
  const me = useCurrentUser()
  const membership = useMembership()
  const navigate = useNavigate()

  // Not a member of the business in the URL: back to the chooser, which
  // only lists the user's own businesses. The API refuses it anyway (T32).
  if (!membership) {
    return <Navigate to="/app" replace />
  }

  const items = PANEL_AREAS.filter((area) => area.roles.includes(membership.role)).map(
    (area) => ({ to: `/app/${membership.slug}/${area.path}`, label: t(area.labelKey) }),
  )

  const header =
    me.memberships.length > 1 ? (
      <label className="shell-field">
        {t('panel.switcherLabel')}
        <select
          className="shell-select"
          value={membership.slug}
          onChange={(event) => navigate(`/app/${event.target.value}`)}
        >
          {me.memberships.map((other) => (
            <option key={other.slug} value={other.slug}>
              {other.name}
            </option>
          ))}
        </select>
      </label>
    ) : (
      <strong>{membership.name}</strong>
    )

  return (
    <div className="shell-layout">
      <Sidebar
        header={
          <>
            {header}
            <span className="shell-sidebar__muted">{t(`panel.roles.${membership.role}`)}</span>
          </>
        }
        navLabel={t('panel.navLabel')}
        items={items}
      />
      <main className="shell-main">
        <Outlet />
      </main>
    </div>
  )
}
