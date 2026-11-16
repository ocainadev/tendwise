import { describe, expect, it, vi } from 'vitest'
import type { PublicMessage } from '../../api/publicApi'
import { clearConversation, loadConversation, saveConversation } from './storage'
import { mergeMessages } from './useConversation'

function message(id: number, body = `Message ${id}`): PublicMessage {
  return { id, sender: 'AI', body, createdAt: '2026-11-16T10:00:00Z' }
}

describe('mergeMessages', () => {
  it('adds new messages in id order and keeps each message once', () => {
    const current = [message(1), message(3)]

    const merged = mergeMessages(current, [message(3), message(2), message(4)])

    expect(merged.map((m) => m.id)).toEqual([1, 2, 3, 4])
  })

  it('returns the same list when nothing is new', () => {
    const current = [message(1)]

    expect(mergeMessages(current, [])).toBe(current)
  })
})

describe('conversation storage', () => {
  const conversation = { conversationId: 'conv-1', token: 'tok', greeting: 'Hi' }

  it('saves, loads and clears one conversation per business', () => {
    saveConversation('joes-pizza', conversation)

    expect(loadConversation('joes-pizza')).toEqual(conversation)
    expect(loadConversation('denim-corner')).toBeNull()

    clearConversation('joes-pizza')
    expect(loadConversation('joes-pizza')).toBeNull()
  })

  it('ignores a stored value that is not a conversation', () => {
    window.sessionStorage.setItem('tendwise.demo.conversation.joes-pizza', '{"token": 1')

    expect(loadConversation('joes-pizza')).toBeNull()
  })

  it('works without storage (private mode, blocked site data)', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('Blocked', 'SecurityError')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Blocked', 'SecurityError')
    })

    expect(() => saveConversation('joes-pizza', conversation)).not.toThrow()
    expect(loadConversation('joes-pizza')).toBeNull()
  })
})
