import { useTranslation } from 'react-i18next'

// Placeholder for a panel area whose task is not done yet.
export function ComingSoonPage({ title }: { title: string }) {
  const { t } = useTranslation()
  return (
    <>
      <h1>{title}</h1>
      <p>
        <span className="shell-badge">{t('common.comingSoon')}</span>
      </p>
      <p>{t('common.comingSoonDescription')}</p>
    </>
  )
}
