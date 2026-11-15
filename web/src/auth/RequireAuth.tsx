import { Navigate, Outlet, useLocation } from 'react-router'
import { useTranslation } from 'react-i18next'
import { CenteredMessage } from '../components/Shell/CenteredMessage'
import { useMe } from './session'

// Layout route for every page that needs a logged-in user. Without a session
// it sends the user to /login and remembers where they were, so the login
// page can bring them back.
export function RequireAuth() {
  const { t } = useTranslation()
  const { data: me, isPending, isError, refetch } = useMe()
  const location = useLocation()

  if (isPending) {
    return <CenteredMessage>{t('common.loading')}</CenteredMessage>
  }
  if (isError) {
    return (
      <CenteredMessage>
        <p role="alert">{t('errors.network')}</p>
        <button type="button" className="shell-button" onClick={() => void refetch()}>
          {t('errors.retry')}
        </button>
      </CenteredMessage>
    )
  }
  if (!me) {
    return <Navigate to="/login" replace state={{ from: location }} />
  }
  return <Outlet />
}
