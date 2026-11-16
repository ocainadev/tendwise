import { useEffect, useRef, useState } from 'react'
import type { Business } from '../../businesses'
import { ExampleQuestions } from './ExampleQuestions'
import { MAX_MESSAGE_LENGTH, MessageInput } from './MessageInput'
import { MessageList } from './MessageList'
import { textsFor } from './texts'
import { useConversation, type ConversationOptions } from './useConversation'

interface ConversationViewProps {
  business: Business
  options?: ConversationOptions
}

// One business's conversation: the messages, the error banner and the input.
// The input is never disabled: a message is accepted at once (202) and the
// reply arrives later on the event stream, so the customer can keep writing.
export function ConversationView({ business, options }: ConversationViewProps) {
  const conversation = useConversation(business, options)
  const [draft, setDraft] = useState('')
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const bodyRef = useRef<HTMLElement>(null)
  const texts = textsFor(business.language)

  // Keep the newest message (or the typing indicator) in view.
  useEffect(() => {
    const body = bodyRef.current
    if (body) {
      body.scrollTop = body.scrollHeight
    }
  }, [conversation.messages, conversation.isTyping])

  async function send(text: string) {
    const message = text.trim()
    if (message === '' || message.length > MAX_MESSAGE_LENGTH) {
      return
    }
    setDraft('')
    inputRef.current?.focus()
    const sent = await conversation.send(message)
    if (!sent) {
      // Give the text back so the customer can try again, unless they
      // already started typing something else.
      setDraft((current) => (current === '' ? text : current))
    }
  }

  const showExamples = conversation.messages.length === 0

  return (
    <>
      <main className="chat__body" ref={bodyRef}>
        {showExamples && <ExampleQuestions business={business} onPick={send} />}
        <MessageList
          greeting={conversation.greeting}
          messages={conversation.messages}
          businessName={business.name}
          texts={texts}
          isTyping={conversation.isTyping}
          isWaitingForStaff={conversation.state === 'WAITING'}
        />
      </main>

      {conversation.error && (
        <div className="chat__error" role="alert">
          {conversation.error}
        </div>
      )}

      <MessageInput ref={inputRef} value={draft} onChange={setDraft} onSend={() => send(draft)} />
    </>
  )
}
