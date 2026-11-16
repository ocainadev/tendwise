import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import type { ConversationDetail, Handoff } from '../api/inbox'
import { useConversationDetail } from './queries'

interface ContextPanelProps {
  slug: string
  conversationId: string
  // Phone only: the link back to the transcript.
  backUrl: string
}

// What staff need to know before answering, read from the conversation
// detail: the handoff (reason and the AI's summary), the AI's last intent and
// language, the order codes it saw and the documents it used.
// It shares the transcript's query, so it costs no extra request.
export function ContextPanel({ slug, conversationId, backUrl }: ContextPanelProps) {
  const { t, i18n } = useTranslation()
  const { data } = useConversationDetail(slug, conversationId)
  if (!data) {
    return null
  }
  const { handoff, intent, language, orderCodes, sources } = readContext(data)
  const reasonKey = handoff ? `inbox.reasons.${handoff.reason}` : ''

  return (
    <div className="inbox-context">
      <div className="inbox-mobile-nav">
        <Link to={backUrl}>{t('inbox.backToConversation')}</Link>
      </div>

      <section>
        <h2>{t('inbox.context.handoff')}</h2>
        {handoff ? (
          <dl>
            <dt>{t('inbox.context.reason')}</dt>
            <dd>{i18n.exists(reasonKey) ? t(reasonKey) : handoff.reason}</dd>
            <dt>{t('inbox.context.summary')}</dt>
            <dd>{handoff.summary || t('inbox.context.noSummary')}</dd>
          </dl>
        ) : (
          <p className="inbox-muted">{t('inbox.context.noHandoff')}</p>
        )}
      </section>

      <section>
        <h2>{t('inbox.context.details')}</h2>
        <dl>
          <dt>{t('inbox.context.intent')}</dt>
          <dd>{intent ?? t('inbox.context.unknown')}</dd>
          <dt>{t('inbox.context.language')}</dt>
          <dd>{language?.toUpperCase() ?? t('inbox.context.unknown')}</dd>
        </dl>
      </section>

      <section>
        <h2>{t('inbox.context.orders')}</h2>
        {orderCodes.length > 0 ? (
          <ul>
            {orderCodes.map((code) => (
              <li key={code}>{code}</li>
            ))}
          </ul>
        ) : (
          <p className="inbox-muted">{t('inbox.context.noOrders')}</p>
        )}
      </section>

      <section>
        <h2>{t('inbox.context.sources')}</h2>
        {sources.length > 0 ? (
          <ul>
            {sources.map((source) => (
              <li key={source}>{source}</li>
            ))}
          </ul>
        ) : (
          <p className="inbox-muted">{t('inbox.context.noSources')}</p>
        )}
      </section>
    </div>
  )
}

interface ConversationContext {
  handoff: Handoff | null
  intent: string | null
  language: string | null
  orderCodes: string[]
  // "Title › Section", each once, in the order the AI first used them.
  sources: string[]
}

export function readContext(conversation: ConversationDetail): ConversationContext {
  // The handoff still being handled, else the newest one.
  const open = conversation.handoffs.filter((h) => h.status === 'OPEN' || h.status === 'IN_PROGRESS')
  const handoff = open.at(-1) ?? conversation.handoffs.at(-1) ?? null

  const aiMessages = conversation.messages.filter((message) => message.sender === 'AI')
  const lastAi = aiMessages.at(-1)?.metadata
  const orderCodes = new Set<string>()
  const sources = new Set<string>()
  for (const message of aiMessages) {
    const metadata = message.metadata
    if (metadata?.orderCode) {
      orderCodes.add(metadata.orderCode)
    }
    for (const source of metadata?.sources ?? []) {
      if (source.title) {
        sources.add(source.section ? `${source.title} › ${source.section}` : source.title)
      }
    }
  }

  return {
    handoff,
    intent: lastAi?.intent ?? null,
    language: lastAi?.language ?? conversation.language,
    orderCodes: [...orderCodes],
    sources: [...sources],
  }
}
