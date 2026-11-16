// TanStack Query hooks for the inbox. Every key starts with
// ['inbox', slug], so the event stream (usePanelEvents) can refetch all
// lists, or one conversation, without knowing which tab is open.

import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { getConversation, listConversations } from '../api/inbox'
import type { InboxTab } from './tabs'

export function inboxKey(slug: string) {
  return ['inbox', slug]
}

// Every tab's list (and so the counts, which come with each page).
export function listsKey(slug: string) {
  return ['inbox', slug, 'list']
}

export function conversationKey(slug: string, id: string) {
  return ['inbox', slug, 'conversation', id]
}

// The list of one tab, page by page (infinite scroll through `nextCursor`).
export function useConversationList(slug: string, tab: InboxTab) {
  return useInfiniteQuery({
    queryKey: [...listsKey(slug), tab.key],
    queryFn: ({ pageParam }) => listConversations(slug, tab.filter, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor,
  })
}

export function useConversationDetail(slug: string, id: string | null) {
  return useQuery({
    queryKey: conversationKey(slug, id ?? ''),
    queryFn: () => getConversation(slug, id ?? ''),
    enabled: id !== null,
  })
}
