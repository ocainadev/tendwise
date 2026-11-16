import { useTranslation } from 'react-i18next'
import type { DocumentStatus } from '../api/knowledge'

// PENDING / INDEXED / FAILED, as a coloured pill.
export function StatusBadge({ status }: { status: DocumentStatus }) {
  const { t } = useTranslation()
  return (
    <span className={`knowledge-status knowledge-status--${status.toLowerCase()}`}>
      {t(`knowledge.statuses.${status}`)}
    </span>
  )
}
