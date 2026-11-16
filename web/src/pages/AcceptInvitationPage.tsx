import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useLocation, useNavigate } from 'react-router'
import { fetchMe, refreshSession } from '../api/auth'
import { ApiError } from '../api/client'
import { acceptInvitation, ACCOUNT_DETAILS_REQUIRED, type AcceptedInvitation } from '../api/team'
import { ME_QUERY_KEY, useMe } from '../auth/session'
import { CenteredMessage } from '../components/Shell/CenteredMessage'

export const MIN_PASSWORD_LENGTH = 8

// The token is in the fragment (#token=…) of the email link, so it never
// reaches a server log (docs/architecture.md, T34).
function tokenFrom(hash: string): string | null {
  return new URLSearchParams(hash.replace(/^#/, '')).get('token')
}

// /invitations/accept#token=…: public. A new user gives a name and a password;
// an existing user just accepts. Accepting sets no cookies, so a logged-out
// user goes to the login page (and from there into the panel), and a
// logged-in user refreshes the session to see the new business.
export function AcceptInvitationPage() {
  const { t } = useTranslation()
  const location = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: me, isPending } = useMe()
  const [token] = useState(() => tokenFrom(location.hash))
  const [name, setName] = useState('')
  const [password, setPassword] = useState('')

  async function goToPanel(accepted: AcceptedInvitation) {
    const panelPath = `/app/${accepted.slug}`
    if (me && (await refreshSession())) {
      await queryClient.fetchQuery({ queryKey: ME_QUERY_KEY, queryFn: fetchMe, staleTime: 0 })
      navigate(panelPath, { replace: true })
      return
    }
    // RequireAuth's convention: the login page returns to state.from.
    navigate('/login', {
      replace: true,
      state: { from: { pathname: panelPath, search: '', hash: '' }, invitedTo: accepted.businessName },
    })
  }

  const accept = useMutation({
    mutationFn: (details: { name: string; password: string } | null) =>
      acceptInvitation(token ?? '', details?.name ?? null, details?.password ?? null),
    onSuccess: (accepted) => goToPanel(accepted),
  })

  if (isPending) {
    return <CenteredMessage>{t('common.loading')}</CenteredMessage>
  }

  if (!token) {
    return (
      <CenteredMessage>
        <main className="shell-card">
          <h1>{t('invitation.title')}</h1>
          <p className="shell-error" role="alert">
            {t('invitation.errors.missingToken')}
          </p>
        </main>
      </CenteredMessage>
    )
  }

  function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    accept.mutate({ name: name.trim(), password })
  }

  let errorText: string | null = null
  if (accept.error) {
    const error = accept.error
    if (error instanceof ApiError && error.status === 0) {
      errorText = t('errors.network')
    } else if (error instanceof ApiError && error.problem.title === ACCOUNT_DETAILS_REQUIRED) {
      errorText = t('invitation.errors.accountRequired')
    } else if (error instanceof ApiError && error.status === 400) {
      errorText = t('invitation.errors.invalid')
    } else {
      errorText = t('errors.generic')
    }
  }

  const error = errorText && (
    <p className="shell-error" role="alert">
      {errorText}
    </p>
  )

  // Logged in: the invitation joins this account (if it was sent to its email).
  if (me) {
    return (
      <CenteredMessage>
        <main className="shell-card">
          <h1>{t('invitation.title')}</h1>
          <p>{t('invitation.loggedInAs', { name: me.user.name })}</p>
          {error}
          <button type="button" className="shell-button" disabled={accept.isPending} onClick={() => accept.mutate(null)}>
            {accept.isPending ? t('invitation.accepting') : t('invitation.accept')}
          </button>
        </main>
      </CenteredMessage>
    )
  }

  return (
    <CenteredMessage>
      <main className="shell-card">
        <h1>{t('invitation.title')}</h1>
        <p>{t('invitation.newAccount')}</p>
        <form className="shell-form" onSubmit={handleCreate}>
          <label className="shell-field">
            {t('invitation.name')}
            <input
              type="text"
              autoComplete="name"
              required
              maxLength={120}
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </label>
          <label className="shell-field">
            {t('invitation.password')}
            <input
              type="password"
              autoComplete="new-password"
              required
              minLength={MIN_PASSWORD_LENGTH}
              maxLength={1024}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
            <span>{t('invitation.passwordHint')}</span>
          </label>
          {error}
          <button type="submit" className="shell-button" disabled={accept.isPending}>
            {accept.isPending ? t('invitation.accepting') : t('invitation.createAndAccept')}
          </button>
        </form>
        <p>{t('invitation.existingAccount')}</p>
        <button
          type="button"
          className="shell-button shell-button--secondary"
          disabled={accept.isPending}
          onClick={() => accept.mutate(null)}
        >
          {t('invitation.acceptWithAccount')}
        </button>
      </main>
    </CenteredMessage>
  )
}
