import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { formatDateTime } from './format'
import { useDocuments, useErrorMessage, useReindex } from './queries'
import { StatusBadge } from './StatusBadge'

interface Props {
  slug: string
  isOwner: boolean
}

// The business's documents with their indexing status. Polls while any is PENDING.
export function DocumentListPage({ slug, isOwner }: Props) {
  const { t } = useTranslation()
  const errorMessage = useErrorMessage()
  const documents = useDocuments(slug)
  const reindex = useReindex(slug)

  return (
    <>
      <div className="knowledge-header">
        <div>
          <h1>{t('knowledge.title')}</h1>
          <p className="knowledge-muted">{t('knowledge.description')}</p>
        </div>
        {isOwner && (
          <div className="knowledge-actions">
            <button
              type="button"
              className="shell-button shell-button--secondary"
              disabled={reindex.isPending}
              onClick={() => reindex.mutate()}
            >
              {reindex.isPending ? t('knowledge.reindexing') : t('knowledge.reindex')}
            </button>
            <Link className="shell-button" to="new">
              {t('knowledge.newDocument')}
            </Link>
          </div>
        )}
      </div>

      {!isOwner && <p className="knowledge-muted">{t('knowledge.readOnly')}</p>}
      {reindex.isSuccess && (
        <p role="status">{t('knowledge.reindexQueued', { count: reindex.data.queued })}</p>
      )}
      {reindex.isError && <p className="shell-error" role="alert">{errorMessage(reindex.error)}</p>}

      {documents.isPending && <p>{t('common.loading')}</p>}
      {documents.isError && (
        <div className="shell-error" role="alert">
          <p>{errorMessage(documents.error)}</p>
          <button type="button" className="shell-button shell-button--secondary" onClick={() => void documents.refetch()}>
            {t('errors.retry')}
          </button>
        </div>
      )}
      {documents.isSuccess && documents.data.length === 0 && <p>{t('knowledge.empty')}</p>}
      {documents.isSuccess && documents.data.length > 0 && (
        <table className="knowledge-table">
          <thead>
            <tr>
              <th scope="col">{t('knowledge.columns.title')}</th>
              <th scope="col">{t('knowledge.columns.source')}</th>
              <th scope="col">{t('knowledge.columns.status')}</th>
              <th scope="col">{t('knowledge.columns.updatedAt')}</th>
            </tr>
          </thead>
          <tbody>
            {documents.data.map((document) => (
              <tr key={document.id}>
                <td>
                  <Link to={String(document.id)}>{document.title}</Link>
                </td>
                <td>{t(`knowledge.sources.${document.source}`)}</td>
                <td>
                  <StatusBadge status={document.status} />
                </td>
                <td>{formatDateTime(document.updatedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
