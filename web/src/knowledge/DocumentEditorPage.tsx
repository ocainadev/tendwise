import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams } from 'react-router'
import { MAX_DOCUMENT_CHARACTERS, MAX_TITLE_CHARACTERS, type DocumentDetail } from '../api/knowledge'
import { formatDateTime } from './format'
import { Markdown } from './Markdown'
import { useDeleteDocument, useDocument, useErrorMessage, useSaveDocument } from './queries'
import { StatusBadge } from './StatusBadge'

interface Props {
  slug: string
  isOwner: boolean
}

// knowledge/new and knowledge/:documentId. OWNER edits (markdown on the left,
// live preview on the right); AGENT only sees the rendered document.
export function DocumentEditorPage({ slug, isOwner }: Props) {
  const { t } = useTranslation()
  const errorMessage = useErrorMessage()
  const { documentId } = useParams()
  const id = documentId === undefined ? null : Number(documentId)
  const document = useDocument(slug, id !== null && Number.isInteger(id) ? id : null)

  const back = (
    <Link to=".." relative="path">
      ← {t('knowledge.editor.back')}
    </Link>
  )

  if (id === null) {
    return (
      <>
        {back}
        <h1>{t('knowledge.editor.newTitle')}</h1>
        <DocumentForm slug={slug} document={null} />
      </>
    )
  }
  if (!Number.isInteger(id) || document.isError) {
    return (
      <>
        {back}
        <p className="shell-error" role="alert">
          {document.isError ? errorMessage(document.error) : t('knowledge.errors.notFound')}
        </p>
      </>
    )
  }
  if (document.isPending) {
    return <p>{t('common.loading')}</p>
  }

  const current = document.data
  return (
    <>
      {back}
      <h1>{isOwner ? t('knowledge.editor.editTitle') : current.title}</h1>
      <p className="knowledge-status-line">
        <StatusBadge status={current.status} />
        <span className="knowledge-muted">
          {t(`knowledge.statusHint.${current.status}`)} · {t('knowledge.columns.updatedAt')}{' '}
          {formatDateTime(current.updatedAt)}
        </span>
      </p>
      {isOwner ? (
        // Keyed by id: the form starts from the loaded document and keeps its
        // own state while the status is polled.
        <DocumentForm key={current.id} slug={slug} document={current} />
      ) : (
        <div className="knowledge-preview">
          <Markdown text={current.content} />
        </div>
      )}
    </>
  )
}

function DocumentForm({ slug, document }: { slug: string; document: DocumentDetail | null }) {
  const { t } = useTranslation()
  const errorMessage = useErrorMessage()
  const navigate = useNavigate()
  const [title, setTitle] = useState(document?.title ?? '')
  const [content, setContent] = useState(document?.content ?? '')
  const [missingFields, setMissingFields] = useState(false)
  const save = useSaveDocument(slug, document?.id ?? null)

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (title.trim() === '' || content.trim() === '') {
      setMissingFields(true)
      return
    }
    setMissingFields(false)
    save.mutate(
      { title: title.trim(), content },
      {
        onSuccess: (saved) => {
          if (document === null) {
            // A new document: open it, so its status can be followed.
            navigate(`../${saved.id}`, { relative: 'path', replace: true })
          }
        },
      },
    )
  }

  return (
    <>
      <form className="knowledge-form" onSubmit={handleSubmit}>
        <label className="shell-field">
          {t('knowledge.editor.titleLabel')}
          <input
            value={title}
            maxLength={MAX_TITLE_CHARACTERS}
            onChange={(event) => setTitle(event.target.value)}
          />
        </label>
        <div className="knowledge-editor">
          <div className="shell-field">
            <label className="shell-field">
              {t('knowledge.editor.contentLabel')}
              <textarea
                value={content}
                maxLength={MAX_DOCUMENT_CHARACTERS}
                rows={20}
                onChange={(event) => setContent(event.target.value)}
              />
            </label>
            <span className="knowledge-muted knowledge-small">
              {t('knowledge.editor.characters', {
                count: content.length,
                max: MAX_DOCUMENT_CHARACTERS,
              })}
            </span>
          </div>
          <section className="knowledge-preview" aria-label={t('knowledge.editor.preview')}>
            <h2 className="knowledge-preview__title">{t('knowledge.editor.preview')}</h2>
            {content.trim() === '' ? (
              <p className="knowledge-muted">{t('knowledge.editor.previewEmpty')}</p>
            ) : (
              <Markdown text={content} />
            )}
          </section>
        </div>
        {missingFields && (
          <p className="shell-error" role="alert">
            {t('knowledge.editor.required')}
          </p>
        )}
        {save.isError && (
          <p className="shell-error" role="alert">
            {errorMessage(save.error)}
          </p>
        )}
        {save.isSuccess && <p role="status">{t('knowledge.editor.saved')}</p>}
        <div className="knowledge-actions">
          <button type="submit" className="shell-button" disabled={save.isPending}>
            {save.isPending ? t('knowledge.editor.saving') : t('knowledge.editor.save')}
          </button>
        </div>
      </form>
      {document && <DeleteDocument slug={slug} document={document} />}
    </>
  )
}

// "Excluir" first asks for confirmation, inline, before anything is deleted.
function DeleteDocument({ slug, document }: { slug: string; document: DocumentDetail }) {
  const { t } = useTranslation()
  const errorMessage = useErrorMessage()
  const navigate = useNavigate()
  const [confirming, setConfirming] = useState(false)
  const remove = useDeleteDocument(slug)

  if (!confirming) {
    return (
      <div className="knowledge-actions knowledge-danger-zone">
        <button
          type="button"
          className="shell-button shell-button--danger"
          onClick={() => setConfirming(true)}
        >
          {t('knowledge.editor.delete')}
        </button>
      </div>
    )
  }

  return (
    <div className="knowledge-confirm knowledge-danger-zone" role="alertdialog" aria-label={t('knowledge.editor.delete')}>
      <p>{t('knowledge.editor.deleteConfirm')}</p>
      {document.source === 'FILE' && <p className="knowledge-muted">{t('knowledge.editor.deleteFileNote')}</p>}
      {remove.isError && (
        <p className="shell-error" role="alert">
          {errorMessage(remove.error)}
        </p>
      )}
      <div className="knowledge-actions">
        <button
          type="button"
          className="shell-button shell-button--danger"
          disabled={remove.isPending}
          onClick={() =>
            remove.mutate(document.id, {
              onSuccess: () => navigate('..', { relative: 'path', replace: true }),
            })
          }
        >
          {remove.isPending ? t('knowledge.editor.deleting') : t('knowledge.editor.deleteConfirmButton')}
        </button>
        <button
          type="button"
          className="shell-button shell-button--secondary"
          disabled={remove.isPending}
          onClick={() => setConfirming(false)}
        >
          {t('knowledge.editor.cancel')}
        </button>
      </div>
    </div>
  )
}
