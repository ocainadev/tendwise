import { useEffect, useRef, useState } from 'react'
import { sendMessage } from '../../api/client'
import { BUSINESSES } from '../../businesses'
import { BusinessPicker } from './BusinessPicker'
import { ExampleQuestions } from './ExampleQuestions'
import { MAX_MESSAGE_LENGTH, MessageInput } from './MessageInput'
import { MessageList, type ChatMessage } from './MessageList'
import './Chat.css'

// The whole chat screen. It owns all the state; the child components only
// display it and report what the customer did.
export function Chat() {
  const [businessSlug, setBusinessSlug] = useState(BUSINESSES[0].slug)
  // null until the first reply; then sent back so the agent keeps the context.
  const [conversationId, setConversationId] = useState<string | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [draft, setDraft] = useState('')
  const [isWaiting, setIsWaiting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const inputRef = useRef<HTMLTextAreaElement>(null)
  const bodyRef = useRef<HTMLElement>(null)

  const business = BUSINESSES.find((b) => b.slug === businessSlug) ?? BUSINESSES[0]

  // Keep the newest message (or the typing indicator) in view.
  useEffect(() => {
    const body = bodyRef.current
    if (body) {
      body.scrollTop = body.scrollHeight
    }
  }, [messages, isWaiting])

  function handleBusinessChange(slug: string) {
    // Another business means another conversation: start from scratch.
    setBusinessSlug(slug)
    setConversationId(null)
    setMessages([])
    setDraft('')
    setError(null)
  }

  async function send(text: string) {
    const message = text.trim()
    if (message === '' || message.length > MAX_MESSAGE_LENGTH || isWaiting) {
      return
    }

    setMessages((current) => [...current, { role: 'customer', text: message }])
    setDraft('')
    setError(null)
    setIsWaiting(true)

    try {
      const response = await sendMessage({
        conversation_id: conversationId,
        business_slug: business.slug,
        message,
      })
      setConversationId(response.conversation_id)
      setMessages((current) => [
        ...current,
        {
          role: 'clerk',
          text: response.reply,
          language: response.language,
          sources: response.sources,
          handoff: response.handoff,
        },
      ])
    } catch (err) {
      // The message was not answered: take it out of the list and give it
      // back to the input, so the customer can simply try again.
      setMessages((current) => current.slice(0, -1))
      setDraft(text)
      setError(err instanceof Error ? err.message : 'Something went wrong. Please try again.')
    } finally {
      setIsWaiting(false)
      inputRef.current?.focus()
    }
  }

  return (
    <div className="chat">
      <header className="chat__header">
        <div>
          <h1 className="chat__title">Tendwise</h1>
          <p className="chat__subtitle">AI customer-service clerk · demo</p>
        </div>
        <BusinessPicker
          businesses={BUSINESSES}
          value={business.slug}
          onChange={handleBusinessChange}
          // Switching mid-request would drop the reply into the wrong chat.
          disabled={isWaiting}
        />
      </header>

      <main className="chat__body" ref={bodyRef}>
        {messages.length === 0 && <ExampleQuestions business={business} onPick={send} />}
        <MessageList messages={messages} businessName={business.name} isWaiting={isWaiting} />
      </main>

      {error && (
        <div className="chat__error" role="alert">
          {error}
        </div>
      )}

      <MessageInput
        ref={inputRef}
        value={draft}
        onChange={setDraft}
        onSend={() => send(draft)}
        isWaiting={isWaiting}
      />
    </div>
  )
}
