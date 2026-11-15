import { useTranslation } from 'react-i18next'
import { Navigate, Outlet } from 'react-router'
import { homePath, useCurrentUser } from '../auth/session'
import { Sidebar } from '../components/Shell/Sidebar'

// The admin areas, in menu order. Placeholders until their tasks land
// (overview T65, businesses and plans T62, audit log T64).
export const ADMIN_AREAS = [
  { path: 'overview', labelKey: 'admin.menu.overview' },
  { path: 'businesses', labelKey: 'admin.menu.businesses' },
  { path: 'plans', labelKey: 'admin.menu.plans' },
  { path: 'audit-log', labelKey: 'admin.menu.auditLog' },
]

// /admin/*: only for platform admins. Anyone else goes to their own home;
// the admin API answers 403 to them anyway.
export function AdminLayout() {
  const { t } = useTranslation()
  const me = useCurrentUser()

  if (me.platformRole !== 'ADMIN') {
    return <Navigate to={homePath(me)} replace />
  }

  const items = ADMIN_AREAS.map((area) => ({ to: `/admin/${area.path}`, label: t(area.labelKey) }))

  return (
    <div className="shell-layout">
      <Sidebar header={<strong>{t('admin.title')}</strong>} navLabel={t('admin.navLabel')} items={items} />
      <main className="shell-main">
        <Outlet />
      </main>
    </div>
  )
}
