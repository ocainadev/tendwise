// Keeps the demo's conversation (id + token) in sessionStorage, one per
// business, so a page refresh continues the same conversation. sessionStorage
// ends with the tab, like a widget conversation should. Storage can be
// missing or throw (private mode, blocked site data), so every access is
// wrapped: without it the chat still works, it just starts over on refresh.

import type { StartedConversation } from '../../api/publicApi'

const PREFIX = 'tendwise.demo.conversation.'

export function loadConversation(slug: string): StartedConversation | null {
  try {
    const raw = window.sessionStorage.getItem(PREFIX + slug)
    if (raw === null) {
      return null
    }
    const value: unknown = JSON.parse(raw)
    if (isStartedConversation(value)) {
      return value
    }
  } catch {
    // Unreadable or not JSON: start a new conversation.
  }
  return null
}

export function saveConversation(slug: string, conversation: StartedConversation): void {
  try {
    window.sessionStorage.setItem(PREFIX + slug, JSON.stringify(conversation))
  } catch {
    // Not saved: a refresh starts a new conversation.
  }
}

export function clearConversation(slug: string): void {
  try {
    window.sessionStorage.removeItem(PREFIX + slug)
  } catch {
    // Nothing to clear.
  }
}

function isStartedConversation(value: unknown): value is StartedConversation {
  return (
    typeof value === 'object' &&
    value !== null &&
    'conversationId' in value &&
    typeof value.conversationId === 'string' &&
    'token' in value &&
    typeof value.token === 'string' &&
    'greeting' in value &&
    typeof value.greeting === 'string'
  )
}
