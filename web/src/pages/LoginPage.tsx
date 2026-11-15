import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, useLocation, type Location } from 'react-router'
import { ApiError } from '../api/client'
import { CenteredMessage } from '../components/Shell/CenteredMessage'
import { homePath, useLogin, useMe } from '../auth/session'

// RequireAuth puts the page the user tried to open in the navigation state.
function returnPath(state: unknown): string | null {
  const from = (state as { from?: Location } | null)?.from
  if (!from || !from.pathname.startsWith('/') || from.pathname === '/login') {
    return null
  }
  return from.pathname + from.search + from.hash
}

export function LoginPage() {
  const { t } = useTranslation()
  const location = useLocation()
  const { data: me, isPending } = useMe()
  const loginMutation = useLogin()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')

  if (isPending) {
    return <CenteredMessage>{t('common.loading')}</CenteredMessage>
  }
  // Logged in (already, or just now): go back to where the user was, or home.
  if (me) {
    return <Navigate to={returnPath(location.state) ?? homePath(me)} replace />
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    loginMutation.mutate({ email: email.trim(), password })
  }

  let errorText: string | null = null
  if (loginMutation.error) {
    const status = loginMutation.error instanceof ApiError ? loginMutation.error.status : -1
    if (status === 401) {
      errorText = t('login.invalidCredentials')
    } else if (status === 0) {
      errorText = t('errors.network')
    } else {
      errorText = t('errors.generic')
    }
  }

  return (
    <CenteredMessage>
      <main className="shell-card">
        <h1>{t('login.title')}</h1>
        <form className="shell-form" onSubmit={handleSubmit}>
          <label className="shell-field">
            {t('login.email')}
            <input
              type="email"
              autoComplete="username"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </label>
          <label className="shell-field">
            {t('login.password')}
            <input
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>
          {errorText && (
            <p className="shell-error" role="alert">
              {errorText}
            </p>
          )}
          <button type="submit" className="shell-button" disabled={loginMutation.isPending}>
            {loginMutation.isPending ? t('login.submitting') : t('login.submit')}
          </button>
        </form>
      </main>
    </CenteredMessage>
  )
}
