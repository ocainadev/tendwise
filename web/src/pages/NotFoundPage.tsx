import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { CenteredMessage } from '../components/Shell/CenteredMessage'

export function NotFoundPage() {
  const { t } = useTranslation()
  return (
    <CenteredMessage>
      <h1>{t('errors.notFound')}</h1>
      <Link to="/">{t('errors.backHome')}</Link>
    </CenteredMessage>
  )
}
