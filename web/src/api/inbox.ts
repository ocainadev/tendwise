// Client for the panel inbox read API of business-service (T43):
// GET /api/v1/panel/{slug}/conversations (one page of a tab) and
// GET /api/v1/panel/{slug}/conversations/{id} (one conversation with every
// message, internal notes included). The live updates come from the panel
// event stream, GET /api/v1/panel/{slug}/events (T40), see inbox/usePanelEvents.ts.

import { apiJson } from './http'

export type ConversationState = 'AI' | 'WAITING' | 'HUMAN' | 'RESOLVED'
export type Channel = 'WEB' | 'WHATSAPP'
export type Sender = 'CUSTOMER' | 'AI' | 'HUMAN' | 'SYSTEM'
export type HandoffReason = 'COMPLAINT' | 'HUMAN_REQUEST' | 'NEGATIVE_SENTIMENT' | 'LOW_CONFIDENCE'

export interface Assignee {
  id: number
  name: string
}

export interface ConversationItem {
  id: string
  state: ConversationState
  channel: Channel
  language: string | null
  assignee: Assignee | null
  // The newest customer, AI or staff message; null when there is none yet.
  lastMessage: { sender: Sender; preview: string; internal: boolean } | null
  lastMessageAt: string
  unreadCount: number
  handoffReason: HandoffReason | null
  // Only in WAITING: when the conversation started waiting for staff.
  waitingSince: string | null
}

export interface TabCount {
  conversations: number
  unread: number
}

export interface ConversationPage {
  items: ConversationItem[]
  // Pass it as `cursor` for the next page; null on the last page.
  nextCursor: string | null
  // The tab badges; the same on every page, whatever the filters.
  counts: { waiting: TabCount; mine: TabCount; ai: TabCount }
}

// AI messages: intent, language, sources, orderCode. SYSTEM messages: event
// (HANDOFF_CREATED, TAKEN_OVER, …) and its details.
export interface MessageMetadata {
  event?: string
  reason?: string
  intent?: string
  language?: string
  sources?: { title?: string; section?: string | null }[]
  orderCode?: string
  [key: string]: unknown
}

export interface InboxMessage {
  id: number
  sender: Sender
  authorUserId: number | null
  // Set only for HUMAN messages.
  authorName: string | null
  body: string
  // true = staff-only note, never sent to the customer.
  internal: boolean
  metadata: MessageMetadata | null
  createdAt: string
}

export interface Handoff {
  id: number
  reason: HandoffReason
  // The AI's summary of the conversation for staff.
  summary: string | null
  status: string
  createdAt: string
  updatedAt: string
}

export interface ConversationDetail {
  id: string
  state: ConversationState
  channel: Channel
  language: string | null
  assignee: Assignee | null
  unreadCount: number
  createdAt: string
  lastMessageAt: string
  resolvedAt: string | null
  waitingSince: string | null
  // Oldest first.
  messages: InboxMessage[]
  // Oldest first.
  handoffs: Handoff[]
}

function conversationsPath(slug: string): string {
  return `/api/v1/panel/${encodeURIComponent(slug)}/conversations`
}

// `filter` is the tab's query string, e.g. "state=HUMAN&assignee=me".
export function listConversations(
  slug: string,
  filter: string,
  cursor: string | null,
): Promise<ConversationPage> {
  const params = new URLSearchParams(filter)
  if (cursor) {
    params.set('cursor', cursor)
  }
  return apiJson<ConversationPage>(`${conversationsPath(slug)}?${params.toString()}`)
}

export function getConversation(slug: string, id: string): Promise<ConversationDetail> {
  return apiJson<ConversationDetail>(`${conversationsPath(slug)}/${encodeURIComponent(id)}`)
}

export function panelEventsPath(slug: string): string {
  return `/api/v1/panel/${encodeURIComponent(slug)}/events`
}
