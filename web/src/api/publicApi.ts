// Client for business-service's public conversation API (/public/v1/conversations),
// the same API the website widget uses (docs/architecture.md, "Public API").
//
// - start: the business's publishable key → conversation id + token.
// - every later call: the token in `Authorization: Bearer …` (never in the URL).
// - send: 202, the message is stored; the reply arrives later on the event
//   stream (./sse.ts) and is read with the history call.
//
// The URLs are relative: Vite (development) and nginx (Docker) forward
// /public/ to business-service, so the page and the API share one origin.

import { ApiError, type Language } from './client'

const BASE = '/public/v1/conversations'

export type Sender = 'CUSTOMER' | 'AI' | 'HUMAN' | 'SYSTEM'

export type ConversationState = 'AI' | 'WAITING' | 'HUMAN' | 'RESOLVED'

export interface Source {
  title: string
  section: string | null
}

export interface PublicMessage {
  // Increasing: a newer message always has a bigger id.
  id: number
  sender: Sender
  body: string
  createdAt: string
  // HUMAN: who wrote it. SYSTEM TAKEN_OVER: who joined. First name only.
  authorName?: string | null
  // SYSTEM only: what happened (TAKEN_OVER, RELEASED, AUTO_ACK, AI_FAILED…).
  event?: string | null
  // AI only: the knowledge-base sections the reply was based on.
  sources?: Source[] | null
}

export interface StartedConversation {
  conversationId: string
  token: string
  greeting: string
}

export async function startConversation(
  publishableKey: string,
  language: Language,
): Promise<StartedConversation> {
  const response = await request(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ publishableKey, language }),
  })
  return (await response.json()) as StartedConversation
}

// Returns the stored customer message (202 Accepted).
export async function sendCustomerMessage(
  conversationId: string,
  token: string,
  body: string,
): Promise<PublicMessage> {
  const response = await request(`${BASE}/${conversationId}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ body }),
  })
  return (await response.json()) as PublicMessage
}

// The messages after `afterId` (0 = from the start), oldest first. The server
// sends at most 100 per call, so this asks again until a page is not full.
export async function fetchHistory(
  conversationId: string,
  token: string,
  afterId: number,
): Promise<PublicMessage[]> {
  const all: PublicMessage[] = []
  let after = afterId
  for (;;) {
    const response = await request(`${BASE}/${conversationId}/messages?after=${after}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    const page = (await response.json()) as PublicMessage[]
    all.push(...page)
    if (page.length < HISTORY_PAGE_SIZE) {
      return all
    }
    after = page[page.length - 1].id
  }
}

// Same page size as business-service (PublicConversationService.HISTORY_PAGE_SIZE).
export const HISTORY_PAGE_SIZE = 100

export function eventsUrl(conversationId: string): string {
  return `${BASE}/${conversationId}/events`
}

async function request(url: string, init: RequestInit): Promise<Response> {
  let response: Response
  try {
    response = await fetch(url, init)
  } catch {
    throw new ApiError('Could not reach the server. Check your connection and try again.', 0)
  }
  if (!response.ok) {
    throw new ApiError(await readErrorMessage(response), response.status)
  }
  return response
}

// business-service sends errors as RFC 7807 problems: {"title", "detail"}.
export async function readErrorMessage(response: Response): Promise<string> {
  try {
    const body: unknown = await response.json()
    if (typeof body === 'object' && body !== null && 'detail' in body) {
      if (typeof body.detail === 'string' && body.detail !== '') {
        return body.detail
      }
    }
  } catch {
    // The body was not JSON: use the fallback below.
  }

  if (response.status === 429) {
    return 'Too many messages. Please wait a moment and try again.'
  }
  // 502/503/504 come from the proxy (Vite or nginx) when business-service is down.
  if (response.status >= 502 && response.status <= 504) {
    return 'The chat service is not available right now. Please try again in a moment.'
  }
  return `Something went wrong (HTTP ${response.status}). Please try again.`
}
