import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { useMembership } from '../auth/session'
import { ContextPanel } from './ContextPanel'
import { ConversationList } from './ConversationList'
import { useConversationList } from './queries'
import { INBOX_TABS, findTab } from './tabs'
import { Transcript } from './Transcript'
import { usePanelEvents } from './usePanelEvents'
import './Inbox.css'

// /app/:slug/inbox: three columns (list, transcript, context). On a phone only
// one column shows at a time (Inbox.css).
//
// The URL holds the state, so refresh and the back button work:
//   ?tab=waiting|mine|ai|resolved   the open tab (default: waiting)
//   &c=<conversation id>            the open conversation
//   &view=context                   on a phone, the context column instead of the transcript
export function InboxPage() {
  const membership = useMembership()
  const slug = membership?.slug ?? ''
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const tab = findTab(searchParams.get('tab'))
  const selectedId = searchParams.get('c')
  const showContext = searchParams.get('view') === 'context'

  usePanelEvents(slug)
  // The badges come with every list page, so the open tab's query has them.
  const list = useConversationList(slug, tab)
  const counts = list.data?.pages[0]?.counts

  const columnClass = selectedId === null ? 'inbox--list' : showContext ? 'inbox--context' : 'inbox--transcript'

  function urlFor(conversationId: string | null, view?: 'context'): string {
    const params = new URLSearchParams({ tab: tab.key })
    if (conversationId) {
      params.set('c', conversationId)
    }
    if (view) {
      params.set('view', view)
    }
    return `?${params.toString()}`
  }

  return (
    <div className={`inbox ${columnClass}`}>
      <h1 className="visually-hidden">{t('inbox.title')}</h1>
      <section className="inbox-column inbox-column--list" aria-label={t('inbox.title')}>
        <nav className="inbox-tabs" aria-label={t('inbox.tabsLabel')}>
          {INBOX_TABS.map((item) => {
            const count = item.countKey && counts ? counts[item.countKey] : null
            return (
              <Link
                key={item.key}
                to={`?tab=${item.key}`}
                className="inbox-tab"
                aria-current={item.key === tab.key ? 'page' : undefined}
              >
                {t(`inbox.tabs.${item.key}`)}
                {count && <span className="inbox-tab__count">({count.conversations})</span>}
                {count && count.unread > 0 && (
                  <span
                    className="inbox-dot"
                    role="img"
                    aria-label={t('inbox.unreadInTab', { count: count.unread })}
                  />
                )}
              </Link>
            )
          })}
        </nav>
        <ConversationList tab={tab} list={list} selectedId={selectedId} urlFor={(id) => urlFor(id)} />
      </section>

      <section className="inbox-column inbox-column--transcript" aria-label={t('inbox.transcriptLabel')}>
        {selectedId === null ? (
          <p className="inbox-muted inbox-placeholder">{t('inbox.noSelection')}</p>
        ) : (
          <Transcript
            slug={slug}
            conversationId={selectedId}
            backUrl={urlFor(null)}
            contextUrl={urlFor(selectedId, 'context')}
          />
        )}
      </section>

      <aside className="inbox-column inbox-column--context" aria-label={t('inbox.context.label')}>
        {selectedId !== null && (
          <ContextPanel slug={slug} conversationId={selectedId} backUrl={urlFor(selectedId)} />
        )}
      </aside>
    </div>
  )
}
