import type { PublicMessage, Source } from '../../api/publicApi'
import { systemText, type ConversationTexts } from './texts'

interface MessageListProps {
  // The business's welcome text, shown first once a conversation started.
  greeting: string | null
  messages: PublicMessage[]
  businessName: string
  texts: ConversationTexts
  isTyping: boolean
  // The conversation was handed off and nobody took it yet.
  isWaitingForStaff: boolean
}

export function MessageList({
  greeting,
  messages,
  businessName,
  texts,
  isTyping,
  isWaitingForStaff,
}: MessageListProps) {
  return (
    // aria-live makes screen readers read new replies out loud.
    <ol className="message-list" aria-live="polite" aria-label="Conversation">
      {greeting && (
        <li className="message message--clerk">
          <span className="visually-hidden">{businessName}:</span>
          <p className="message__bubble">{greeting}</p>
        </li>
      )}

      {messages.map((message) => (
        <MessageItem key={message.id} message={message} businessName={businessName} texts={texts} />
      ))}

      {isWaitingForStaff && (
        <li className="message message--system">
          <p className="message__handoff">{texts.handoff}</p>
        </li>
      )}

      {isTyping && (
        <li className="message message--clerk">
          <p className="message__typing">{texts.typing(businessName)}</p>
        </li>
      )}
    </ol>
  )
}

interface MessageItemProps {
  message: PublicMessage
  businessName: string
  texts: ConversationTexts
}

function MessageItem({ message, businessName, texts }: MessageItemProps) {
  if (message.sender === 'SYSTEM') {
    // "Maria entrou na conversa", "Você voltou para o assistente", …
    return (
      <li className="message message--system">
        <p className="message__event">{systemText(message, texts)}</p>
      </li>
    )
  }

  if (message.sender === 'CUSTOMER') {
    return (
      <li className="message message--customer">
        <span className="visually-hidden">{texts.you}</span>
        <p className="message__bubble">{message.body}</p>
      </li>
    )
  }

  // AI or HUMAN: a staff message shows who wrote it.
  const author = message.sender === 'HUMAN' ? (message.authorName ?? null) : null
  return (
    <li className={`message message--clerk${author ? ' message--staff' : ''}`}>
      {author ? (
        <span className="message__author">{author}</span>
      ) : (
        <span className="visually-hidden">{businessName}:</span>
      )}
      <p className="message__bubble">{message.body}</p>
      {message.sources && message.sources.length > 0 && (
        <SourceList sources={message.sources} label={texts.sources(message.sources.length)} />
      )}
    </li>
  )
}

// The documents the reply was based on, collapsed so they do not get in the way.
function SourceList({ sources, label }: { sources: Source[]; label: string }) {
  return (
    <details className="message__sources">
      <summary>{label}</summary>
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
