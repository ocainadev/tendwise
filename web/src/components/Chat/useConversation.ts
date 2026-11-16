// The demo chat's conversation with business-service's public API, the way
// the website widget will talk to it (docs/architecture.md, "Public API" and
// "Real-time"):
//
// 1. The first message starts a conversation with the business's publishable
//    key; the id and token are kept in sessionStorage (./storage.ts).
// 2. Each message is a POST that answers 202 at once. The input never waits.
// 3. Replies (AI, staff, system events) arrive on the event stream. The events
//    carry ids only, so on each message.created the hook reads the history
//    after the newest message it has and merges it in.
// 4. When the stream drops, the hook waits a little, reloads the history and
//    reconnects with Last-Event-ID; the server then replays what was missed
//    and the current state.

import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import {
  eventsUrl,
  fetchHistory,
  sendCustomerMessage,
  startConversation,
  type ConversationState,
  type PublicMessage,
  type StartedConversation,
} from '../../api/publicApi'
import { readEventStream, type SseEvent } from '../../api/sse'
import type { Business } from '../../businesses'
import { clearConversation, loadConversation, saveConversation } from './storage'

// Waits before reconnecting: 1 s, 2 s, 5 s, then every 10 s.
const RECONNECT_DELAYS_MS = [1000, 2000, 5000, 10000]

// If no reply arrives, the typing indicator goes away after this long, so it
// never stays on forever (for example when the AI job failed silently).
export const TYPING_TIMEOUT_MS = 60_000

export interface ConversationOptions {
  // Tests pass a short delay; the app uses RECONNECT_DELAYS_MS.
  reconnectDelayMs?: (attempt: number) => number
}

export interface Conversation {
  greeting: string | null
  messages: PublicMessage[]
  state: ConversationState
  // True from "message sent" (or ai.typing) until the reply arrives.
  isTyping: boolean
  error: string | null
  // Resolves to false when the message was not sent (the error says why).
  send: (text: string) => Promise<boolean>
}

// Adds new messages to the list: by id, so a message received twice (the POST
// answer and the history, or a replay after a reconnect) is shown once.
export function mergeMessages(current: PublicMessage[], incoming: PublicMessage[]): PublicMessage[] {
  if (incoming.length === 0) {
    return current
  }
  const byId = new Map(current.map((message) => [message.id, message]))
  for (const message of incoming) {
    byId.set(message.id, message)
  }
  return [...byId.values()].sort((a, b) => a.id - b.id)
}

function defaultReconnectDelay(attempt: number): number {
  return RECONNECT_DELAYS_MS[Math.min(attempt, RECONNECT_DELAYS_MS.length - 1)]
}

function wait(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms)
    signal.addEventListener('abort', () => {
      clearTimeout(timer)
      resolve()
    })
  })
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : 'Something went wrong. Please try again.'
}

export function useConversation(business: Business, options: ConversationOptions = {}): Conversation {
  const reconnectDelayMs = options.reconnectDelayMs ?? defaultReconnectDelay

  // The stored conversation of this business, if the page was refreshed.
  const [session, setSession] = useState<StartedConversation | null>(() =>
    loadConversation(business.slug),
  )
  const [messages, setMessages] = useState<PublicMessage[]>([])
  const [state, setState] = useState<ConversationState>('AI')
  const [isTyping, setIsTyping] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // The newest message id read from the history. The POST answer is not
  // counted: an older staff message could still be unread.
  const lastSyncedId = useRef(0)
  // History reads run one after another, so two at once never race.
  const syncChain = useRef<Promise<void>>(Promise.resolve())
  // The state as of the last render, for send().
  const stateRef = useRef(state)
  useEffect(() => {
    stateRef.current = state
  }, [state])

  // The conversation is gone (unknown id or token): forget it, so the next
  // message starts a new one.
  const forget = useCallback(() => {
    clearConversation(business.slug)
    lastSyncedId.current = 0
    setSession(null)
    setMessages([])
    setState('AI')
    setIsTyping(false)
  }, [business.slug])

  const sync = useCallback(
    (conversation: StartedConversation): Promise<void> => {
      const next = syncChain.current.then(async () => {
        const found = await fetchHistory(
          conversation.conversationId,
          conversation.token,
          lastSyncedId.current,
        )
        if (found.length === 0) {
          return
        }
        lastSyncedId.current = Math.max(lastSyncedId.current, found[found.length - 1].id)
        setMessages((current) => mergeMessages(current, found))
        // A reply from anyone but the customer ends "typing…".
        if (found.some((message) => message.sender !== 'CUSTOMER')) {
          setIsTyping(false)
        }
      })
      // A failed read must not block the next ones.
      syncChain.current = next.catch(() => {})
      return next
    },
    [],
  )

  // The event stream, open while there is a conversation.
  useEffect(() => {
    if (session === null) {
      return
    }
    const conversation = session
    const controller = new AbortController()
    const { signal } = controller

    function handleEvent(event: SseEvent) {
      if (event.type === 'message.created' || event.type === 'resync') {
        sync(conversation).catch(() => {
          // The next event or the reconnect reads it again.
        })
      } else if (event.type === 'conversation.state_changed') {
        const data = JSON.parse(event.data) as { state?: ConversationState }
        if (data.state) {
          setState(data.state)
          if (data.state !== 'AI') {
            setIsTyping(false)
          }
        }
      } else if (event.type === 'ai.typing') {
        setIsTyping(true)
      }
      // Other events (staff.typing from T44, …) are not shown yet.
    }

    async function run() {
      let attempt = 0
      while (!signal.aborted) {
        try {
          // Reload the history first (page refresh or reconnect), then ask the
          // server to replay anything newer and the current state.
          await sync(conversation)
          await readEventStream(eventsUrl(conversation.conversationId), {
            headers: {
              Authorization: `Bearer ${conversation.token}`,
              'Last-Event-ID': String(lastSyncedId.current),
            },
            signal,
            onOpen: () => {
              attempt = 0
            },
            onEvent: handleEvent,
          })
        } catch (err) {
          if (err instanceof ApiError && err.status === 404) {
            forget()
            return
          }
          // Network error or 5xx: try again after the delay below.
        }
        if (!signal.aborted) {
          await wait(reconnectDelayMs(attempt), signal)
          attempt += 1
        }
      }
    }

    void run()
    return () => controller.abort()
  }, [session, sync, forget, reconnectDelayMs])

  // Never leave "typing…" on forever.
  useEffect(() => {
    if (!isTyping) {
      return
    }
    const timer = setTimeout(() => setIsTyping(false), TYPING_TIMEOUT_MS)
    return () => clearTimeout(timer)
  }, [isTyping])

  async function send(text: string): Promise<boolean> {
    setError(null)
    try {
      let conversation = session
      if (conversation === null) {
        conversation = await startConversation(business.publishableKey, business.language)
        saveConversation(business.slug, conversation)
        setSession(conversation)
      }
      const stored = await sendCustomerMessage(conversation.conversationId, conversation.token, text)
      setMessages((current) => mergeMessages(current, [stored]))
      // Only the AI answers at once; staff may take a while.
      if (stateRef.current === 'AI' || stateRef.current === 'RESOLVED') {
        setIsTyping(true)
      }
      return true
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        forget()
        setError('This conversation has ended. Send your message again to start a new one.')
      } else {
        setError(errorText(err))
      }
      return false
    }
  }

  return { greeting: session?.greeting ?? null, messages, state, isTyping, error, send }
}
