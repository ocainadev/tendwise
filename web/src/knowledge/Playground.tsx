import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { askPlayground, type PlaygroundAnswer } from '../api/knowledge'
import { useErrorMessage } from './queries'

interface Exchange {
  question: string
  answer: PlaygroundAnswer
}

// A mini chat with the assistant, for the owner to check an edit right away.
// It calls POST …/playground, whose conversations are stored with channel
// "playground": they never reach the inbox or the metrics, and a handoff is
// only reported, not created.
export function Playground({ slug }: { slug: string }) {
  const { t } = useTranslation()
  const errorMessage = useErrorMessage()
  const [question, setQuestion] = useState('')
  const [exchanges, setExchanges] = useState<Exchange[]>([])
  const conversationId = exchanges.at(-1)?.answer.conversationId ?? null

  const ask = useMutation({
    mutationFn: (message: string) => askPlayground(slug, conversationId, message),
    onSuccess: (answer, message) => {
      setExchanges((previous) => [...previous, { question: message, answer }])
      setQuestion('')
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const message = question.trim()
    if (message !== '' && !ask.isPending) {
      ask.mutate(message)
    }
  }

  function startOver() {
    setExchanges([])
    ask.reset()
  }

  return (
    <aside className="knowledge-playground" aria-labelledby="playground-title">
      <h2 id="playground-title">{t('knowledge.playground.title')}</h2>
      <p className="knowledge-muted">{t('knowledge.playground.description')}</p>
      <p className="knowledge-muted knowledge-small">{t('knowledge.playground.notCounted')}</p>

      <ol className="knowledge-playground__messages" aria-live="polite">
        {exchanges.map((exchange, index) => (
          <li key={index}>
            <p className="knowledge-playground__question">
              <strong>{t('knowledge.playground.you')}:</strong> {exchange.question}
            </p>
            <div className="knowledge-playground__answer">
              <p>
                <strong>{t('knowledge.playground.assistant')}:</strong> {exchange.answer.reply}
              </p>
              {exchange.answer.handoff && (
                <p className="shell-badge">{t('knowledge.playground.handoff')}</p>
              )}
              <p className="knowledge-small">
                <strong>{t('knowledge.playground.sources')}:</strong>
              </p>
              {exchange.answer.sources.length === 0 ? (
                <p className="knowledge-muted knowledge-small">{t('knowledge.playground.noSources')}</p>
              ) : (
                <ul className="knowledge-small">
                  {exchange.answer.sources.map((source, sourceIndex) => (
                    <li key={sourceIndex}>
                      {source.title}
                      {source.section && <span className="knowledge-muted"> — {source.section}</span>}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </li>
        ))}
      </ol>

      {ask.isError && (
        <p className="shell-error" role="alert">
          {errorMessage(ask.error)}
        </p>
      )}

      <form className="knowledge-form" onSubmit={handleSubmit}>
        <label className="shell-field">
          {t('knowledge.playground.questionLabel')}
          <textarea
            rows={2}
            value={question}
            placeholder={t('knowledge.playground.placeholder')}
            onChange={(event) => setQuestion(event.target.value)}
          />
        </label>
        <div className="knowledge-actions">
          <button type="submit" className="shell-button" disabled={ask.isPending || question.trim() === ''}>
            {ask.isPending ? t('knowledge.playground.asking') : t('knowledge.playground.ask')}
          </button>
          {exchanges.length > 0 && (
            <button type="button" className="shell-button shell-button--secondary" onClick={startOver}>
              {t('knowledge.playground.newConversation')}
            </button>
          )}
        </div>
      </form>
    </aside>
  )
}
