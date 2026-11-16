import { useTranslation } from 'react-i18next'
import { ApiError } from '../api/client'

interface ErrorStateProps {
  error: unknown
  onRetry: () => void
}

// A failed request: a translated message and a retry button.
export function ErrorState({ error, onRetry }: ErrorStateProps) {
  const { t } = useTranslation()
  let message = t('errors.generic')
  if (error instanceof ApiError && error.status === 0) {
    message = t('errors.network')
  } else if (error instanceof ApiError && error.status === 404) {
    message = t('inbox.notFound')
  }
  return (
    <div className="inbox-error" role="alert">
      <p>{message}</p>
      <button type="button" className="shell-button" onClick={onRetry}>
        {t('errors.retry')}
      </button>
    </div>
  )
}
