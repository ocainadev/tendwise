import type { ConversationPage } from '../api/inbox'

// The four inbox tabs, in display order. `filter` is the query string the
// list API expects for that tab (docs/architecture.md, T43); `countKey` is
// its badge in the `counts` of the list response (resolved has none).
export type TabKey = 'waiting' | 'mine' | 'ai' | 'resolved'

export interface InboxTab {
  key: TabKey
  filter: string
  countKey: keyof ConversationPage['counts'] | null
}

export const INBOX_TABS: InboxTab[] = [
  { key: 'waiting', filter: 'state=WAITING', countKey: 'waiting' },
  { key: 'mine', filter: 'state=HUMAN&assignee=me', countKey: 'mine' },
  { key: 'ai', filter: 'state=AI', countKey: 'ai' },
  { key: 'resolved', filter: 'state=RESOLVED', countKey: null },
]

export const DEFAULT_TAB: TabKey = 'waiting'

// The tab in the URL (?tab=…), or the default one when it is missing or unknown.
export function findTab(key: string | null): InboxTab {
  return INBOX_TABS.find((tab) => tab.key === key) ?? INBOX_TABS[0]
}
