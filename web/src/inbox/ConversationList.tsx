import type { UseInfiniteQueryResult, InfiniteData } from '@tanstack/react-query'
import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import type { ConversationItem, ConversationPage } from '../api/inbox'
import { ErrorState } from './ErrorState'
import { formatElapsed, formatMessageTime, shortId, useNow } from './format'
import type { InboxTab } from './tabs'

interface ConversationListProps {
  tab: InboxTab
  list: UseInfiniteQueryResult<InfiniteData<ConversationPage>>
  selectedId: string | null
  urlFor: (conversationId: string) => string
}

// The conversations of the open tab, newest first. The next page loads when
// the end of the list scrolls into view (or with the "Carregar mais" button).
export function ConversationList({ tab, list, selectedId, urlFor }: ConversationListProps) {
  const { t } = useTranslation()
  const now = useNow()
  const endRef = useRef<HTMLDivElement>(null)
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = list

  useEffect(() => {
    const end = endRef.current
    // jsdom (tests) has no IntersectionObserver; the button still works there.
    if (!end || !hasNextPage || typeof IntersectionObserver === 'undefined') {
      return
    }
    const observer = new IntersectionObserver((entries) => {
      if (entries.some((entry) => entry.isIntersecting) && !isFetchingNextPage) {
        void fetchNextPage()
      }
    })
    observer.observe(end)
    return () => observer.disconnect()
  }, [hasNextPage, isFetchingNextPage, fetchNextPage])

  if (list.isPending) {
    return <p className="inbox-muted inbox-placeholder">{t('common.loading')}</p>
  }
  if (list.isError) {
    return <ErrorState error={list.error} onRetry={() => void list.refetch()} />
  }

  const items = list.data.pages.flatMap((page) => page.items)
  if (items.length === 0) {
    return <p className="inbox-muted inbox-placeholder">{t(`inbox.empty.${tab.key}`)}</p>
  }

  return (
    <div className="inbox-list">
      <ul className="inbox-list__items">
        {items.map((item) => (
          <li key={item.id}>
            <Row item={item} now={now} selected={item.id === selectedId} to={urlFor(item.id)} />
          </li>
        ))}
      </ul>
      <div ref={endRef} />
      {hasNextPage && (
        <button
          type="button"
          className="inbox-link-button"
          disabled={isFetchingNextPage}
          onClick={() => void fetchNextPage()}
        >
          {isFetchingNextPage ? t('inbox.loadingMore') : t('inbox.loadMore')}
        </button>
      )}
    </div>
  )
}

interface RowProps {
  item: ConversationItem
  now: number
  selected: boolean
  to: string
}

function Row({ item, now, selected, to }: RowProps) {
  const { t } = useTranslation()
  const last = item.lastMessage
  let prefix = ''
  if (last?.internal) {
    prefix = t('inbox.previewPrefix.internal')
  } else if (last?.sender === 'AI' || last?.sender === 'HUMAN') {
    prefix = t(`inbox.previewPrefix.${last.sender}`)
  }

  return (
    <Link to={to} className="inbox-row" aria-current={selected ? 'true' : undefined}>
      <span className="inbox-row__top">
        <strong className="inbox-row__name">{t('inbox.anonymous', { id: shortId(item.id) })}</strong>
        {item.unreadCount > 0 && (
          <span
            className="inbox-dot"
            role="img"
            aria-label={t('inbox.unread', { count: item.unreadCount })}
          />
        )}
        <span className="inbox-row__time">
          {item.state === 'WAITING' && item.waitingSince
            ? t('inbox.waitingFor', { time: formatElapsed(item.waitingSince, now) })
            : formatMessageTime(item.lastMessageAt)}
        </span>
      </span>
      <span className="inbox-row__meta">
        {t(`inbox.channels.${item.channel}`)}
        {item.language && ` · ${item.language.toUpperCase()}`}
      </span>
      <span className="inbox-row__preview">
        {last ? `${prefix}${last.preview}` : t('inbox.noMessages')}
      </span>
    </Link>
  )
}
