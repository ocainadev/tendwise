import { useTranslation } from 'react-i18next'
import { Link, Navigate } from 'react-router'
import { useCurrentUser, useLogout } from '../auth/session'
import { CenteredMessage } from '../components/Shell/CenteredMessage'

// /app: lists the user's businesses. With exactly one there is nothing to
// choose, so it goes straight to that business.
export function BusinessChooserPage() {
  const { t } = useTranslation()
  const me = useCurrentUser()
  const logout = useLogout()

  if (me.memberships.length === 1) {
    return <Navigate to={`/app/${me.memberships[0].slug}`} replace />
  }

  return (
    <CenteredMessage>
      <main className="shell-card">
        <h1>{t('chooser.title')}</h1>
        {me.memberships.length === 0 ? (
          <p>{t('chooser.none')}</p>
        ) : (
          <ul className="shell-list">
            {me.memberships.map((membership) => (
              <li key={membership.slug}>
                <Link to={`/app/${membership.slug}`}>
                  <strong>{membership.name}</strong>
                  <br />
                  <span className="shell-sidebar__muted">{t(`panel.roles.${membership.role}`)}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
        {me.platformRole === 'ADMIN' && (
          <p>
            <Link to="/admin">{t('chooser.adminLink')}</Link>
          </p>
        )}
        <p>
          <button
            type="button"
            className="shell-button shell-button--secondary"
            onClick={() => logout.mutate()}
            disabled={logout.isPending}
          >
            {t('common.logout')}
          </button>
        </p>
      </main>
    </CenteredMessage>
  )
}
