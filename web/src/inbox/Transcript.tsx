import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import type { ConversationDetail, InboxMessage } from '../api/inbox'
import { ErrorState } from './ErrorState'
import { formatElapsed, formatMessageTime, shortId, useNow } from './format'
import { useConversationDetail } from './queries'

interface TranscriptProps {
  slug: string
  conversationId: string
  // Phone only: the links to the list and to the context column.
  backUrl: string
  contextUrl: string
}

// The middle column: the conversation header and every message, oldest first,
// internal notes included. It scrolls to the newest message when one arrives.
export function Transcript({ slug, conversationId, backUrl, contextUrl }: TranscriptProps) {
  const { t } = useTranslation()
  const detail = useConversationDetail(slug, conversationId)
  const endRef = useRef<HTMLDivElement>(null)
  const messageCount = detail.data?.messages.length ?? 0

  useEffect(() => {
    // jsdom (tests) has no scrollIntoView.
    endRef.current?.scrollIntoView?.({ block: 'end' })
  }, [conversationId, messageCount])

  return (
    <div className="inbox-transcript">
      <div className="inbox-mobile-nav">
        <Link to={backUrl}>{t('inbox.backToList')}</Link>
        <Link to={contextUrl}>{t('inbox.showContext')}</Link>
      </div>
      {detail.isPending && <p className="inbox-muted inbox-placeholder">{t('common.loading')}</p>}
      {detail.isError && <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />}
      {detail.data && (
        <>
          <Header conversation={detail.data} />
          <ol className="inbox-messages">
            {detail.data.messages.map((message) => (
              <Message key={message.id} message={message} />
            ))}
          </ol>
          <div ref={endRef} />
        </>
      )}
      {/* T46: the reply box and the action buttons (take over, assign,
          release, resolve) go in this slot, under the messages. */}
      <div className="inbox-reply-slot" data-testid="reply-slot" aria-label={t('inbox.replySlot')} />
    </div>
  )
}

function Header({ conversation }: { conversation: ConversationDetail }) {
  const { t } = useTranslation()
  const now = useNow()
  let state = t(`inbox.states.${conversation.state}`)
  if (conversation.state === 'HUMAN') {
    state = conversation.assignee
      ? t('inbox.states.HUMAN', { name: conversation.assignee.name })
      : t('inbox.states.HUMAN_NOBODY')
  }
  const parts = [
    t(`inbox.channels.${conversation.channel}`),
    conversation.language?.toUpperCase(),
    state,
    conversation.state === 'WAITING' && conversation.waitingSince
      ? t('inbox.waitingFor', { time: formatElapsed(conversation.waitingSince, now) })
      : null,
  ].filter(Boolean)

  return (
    <header className="inbox-transcript__header">
      <h2>{t('inbox.anonymous', { id: shortId(conversation.id) })}</h2>
      <p className="inbox-muted">{parts.join(' · ')}</p>
    </header>
  )
}

// The style of a message: customer, AI, staff, system or internal note.
export function messageKind(message: InboxMessage): string {
  if (message.internal && message.sender === 'HUMAN') {
    return 'internal'
  }
  return message.sender.toLowerCase()
}

function Message({ message }: { message: InboxMessage }) {
  const { t } = useTranslation()
  const kind = messageKind(message)
  const name = message.authorName ?? ''

  let author = t(`inbox.senders.${message.sender}`)
  if (kind === 'internal') {
    author = t('inbox.senders.internal', { name: name || t('inbox.senders.HUMAN_NOBODY') })
  } else if (message.sender === 'HUMAN') {
    author = name ? t('inbox.senders.HUMAN', { name }) : t('inbox.senders.HUMAN_NOBODY')
  }

  return (
    <li className={`inbox-message inbox-message--${kind}`} data-kind={kind}>
      <span className="inbox-message__author">
        {author} · <time dateTime={message.createdAt}>{formatMessageTime(message.createdAt)}</time>
      </span>
      <span className="inbox-message__body">
        {message.sender === 'SYSTEM' ? <SystemText message={message} /> : message.body}
      </span>
    </li>
  )
}

// SYSTEM messages are shown from metadata.event, in the panel's language.
function SystemText({ message }: { message: InboxMessage }) {
  const { t, i18n } = useTranslation()
  const event = message.metadata?.event
  if (event === 'HANDOFF_CREATED') {
    const reasonKey = `inbox.reasons.${message.metadata?.reason}`
    const reason = i18n.exists(reasonKey) ? t(reasonKey) : (message.metadata?.reason ?? '')
    return t('inbox.systemEvents.HANDOFF_CREATED', { reason })
  }
  const key = `inbox.systemEvents.${event}`
  if (event && i18n.exists(key)) {
    return t(key)
  }
  return message.body || t('inbox.systemEvents.other')
}
