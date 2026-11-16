import type { FormEvent, KeyboardEvent, Ref } from 'react'

// Same limit as agent-service's guard on incoming messages (agreed contract).
export const MAX_MESSAGE_LENGTH = 1000

interface MessageInputProps {
  ref: Ref<HTMLTextAreaElement>
  value: string
  onChange: (value: string) => void
  onSend: () => void
}

export function MessageInput({ ref, value, onChange, onSend }: MessageInputProps) {
  const isTooLong = value.length > MAX_MESSAGE_LENGTH
  // Never blocked by a pending reply: with the public API a message is accepted
  // at once (202), so Enter always sends what is in the box.
  const canSend = value.trim() !== '' && !isTooLong

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (canSend) {
      onSend()
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    // Enter sends; Shift+Enter adds a new line. isComposing skips the Enter
    // that confirms a character in IME keyboards (e.g. accents on some layouts).
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      if (canSend) {
        onSend()
      }
    }
  }

  return (
    <form className="message-input" onSubmit={handleSubmit}>
      <label htmlFor="message-input" className="visually-hidden">
        Message
      </label>
      <textarea
        id="message-input"
        ref={ref}
        rows={2}
        value={value}
        placeholder="Type your message…"
        aria-describedby="message-counter"
        aria-invalid={isTooLong}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={handleKeyDown}
      />
      <div className="message-input__actions">
        <span
          id="message-counter"
          className={isTooLong ? 'message-input__counter message-input__counter--over' : 'message-input__counter'}
        >
          {value.length} / {MAX_MESSAGE_LENGTH}
        </span>
        <button type="submit" disabled={!canSend}>
          Send
        </button>
      </div>
    </form>
  )
}
