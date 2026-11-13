import type { Language, Source } from '../../api/client'

export interface ChatMessage {
  role: 'customer' | 'clerk'
  text: string
  // The fields below only exist on clerk replies.
  language?: Language
  sources?: Source[]
  handoff?: boolean
}

interface MessageListProps {
  messages: ChatMessage[]
  businessName: string
  isWaiting: boolean
}

export function MessageList({ messages, businessName, isWaiting }: MessageListProps) {
  return (
    // aria-live makes screen readers read new replies out loud.
    <ol className="message-list" aria-live="polite" aria-label="Conversation">
      {messages.map((message, index) => (
        // The list only grows (or is cleared), so the index is a stable key.
        <li key={index} className={`message message--${message.role}`}>
          <span className="visually-hidden">
            {message.role === 'customer' ? 'You:' : `${businessName}:`}
          </span>
          <p className="message__bubble" lang={message.language}>
            {message.text}
          </p>
          {message.sources && message.sources.length > 0 && (
            <SourceList sources={message.sources} />
          )}
          {message.handoff && (
            <p className="message__handoff">A team member will continue this conversation.</p>
          )}
        </li>
      ))}

      {isWaiting && (
        <li className="message message--clerk">
          <p className="message__typing">{businessName} is typing…</p>
        </li>
      )}
    </ol>
  )
}

// The documents the reply was based on, collapsed so they do not get in the way.
function SourceList({ sources }: { sources: Source[] }) {
  return (
    <details className="message__sources">
      <summary>Sources ({sources.length})</summary>
      <ul>
        {sources.map((source, index) => (
          <li key={index}>
            {source.title}
            {source.section && ` – ${source.section}`}
          </li>
        ))}
      </ul>
    </details>
  )
}
