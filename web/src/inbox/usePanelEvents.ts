// Live updates of the inbox: one connection to the panel event stream
// (GET /api/v1/panel/{slug}/events, T40) per business. Events carry ids only,
// so each one just tells TanStack Query which data is stale; the lists, the
// counts and the open conversation are then fetched again, without a reload.
//
// The panel stream does not replay missed events. After a reconnect (or a
// `resync` event) everything in the inbox is refetched instead.

import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { ME_QUERY_KEY } from '../auth/session'
import { ApiError } from '../api/client'
import { apiFetch } from '../api/http'
import { panelEventsPath } from '../api/inbox'
import { readEventStream, type SseEvent } from '../api/sse'
import { conversationKey, inboxKey, listsKey } from './queries'

// Waits before reconnecting: 1 s, 2 s, 5 s, 10 s, then every 30 s.
// An object so tests can make the waits short.
export const reconnect = { delaysMs: [1000, 2000, 5000, 10000, 30000] }

// Events that change a conversation row, a tab count or the transcript.
// ai.typing and staff.typing change nothing stored, so they are ignored here.
const REFETCH_EVENTS = [
  'message.created',
  'conversation.state_changed',
  'conversation.assigned',
  'conversation.read',
  'handoff.created',
]

function wait(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms)
    signal.addEventListener('abort', () => {
      clearTimeout(timer)
      resolve()
    })
  })
}

export function usePanelEvents(slug: string) {
  const queryClient = useQueryClient()

  useEffect(() => {
    const controller = new AbortController()
    const { signal } = controller
    // The newest message id seen; sent back as Last-Event-ID on reconnect.
    let lastEventId: string | null = null

    function refetchAll() {
      void queryClient.invalidateQueries({ queryKey: inboxKey(slug) })
    }

    function handleEvent(event: SseEvent) {
      if (event.id) {
        lastEventId = event.id
      }
      if (event.type === 'resync') {
        refetchAll()
        return
      }
      if (!REFETCH_EVENTS.includes(event.type)) {
        return
      }
      const data = JSON.parse(event.data) as { conversationId?: string }
      void queryClient.invalidateQueries({ queryKey: listsKey(slug) })
      if (data.conversationId) {
        void queryClient.invalidateQueries({ queryKey: conversationKey(slug, data.conversationId) })
      }
    }

    async function run() {
      let attempt = 0
      let connectedBefore = false
      while (!signal.aborted) {
        try {
          await readEventStream(panelEventsPath(slug), {
            headers: lastEventId ? { 'Last-Event-ID': lastEventId } : {},
            signal,
            onOpen: () => {
              attempt = 0
              // Events sent while we were away are lost: read everything again.
              if (connectedBefore) {
                refetchAll()
              }
              connectedBefore = true
            },
            onEvent: handleEvent,
          })
        } catch (error) {
          if (error instanceof ApiError && error.status === 401) {
            // The access token expired. apiFetch refreshes the session; if
            // that fails too, the session ended and the login page opens.
            const me = await apiFetch('/auth/me').catch(() => null)
            if (me?.status === 401) {
              queryClient.setQueryData(ME_QUERY_KEY, null)
              return
            }
          }
          // Otherwise (network error, 5xx): try again after the wait below.
        }
        if (!signal.aborted) {
          const delays = reconnect.delaysMs
          await wait(delays[Math.min(attempt, delays.length - 1)], signal)
          attempt += 1
        }
      }
    }

    void run()
    return () => controller.abort()
  }, [slug, queryClient])
}
