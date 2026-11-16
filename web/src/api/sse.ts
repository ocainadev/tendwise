// A small Server-Sent Events reader on top of fetch. EventSource cannot send
// an Authorization header, and the conversation token must not go in the URL,
// so the demo (and later the widget) reads the stream with fetch instead.
//
// Format (https://html.spec.whatwg.org/multipage/server-sent-events.html):
// lines of "field: value"; a blank line ends one event; a line starting with
// ":" is a comment (business-service sends ": heartbeat" every 25 seconds).

import { ApiError } from './client'
import { readErrorMessage } from './publicApi'

export interface SseEvent {
  // "message" when the server sent no "event:" line.
  type: string
  data: string
  // The "id:" line of this event, or null when it had none.
  id: string | null
}

// Turns text chunks into events. A chunk can end in the middle of a line or of
// an event, so the unfinished part is kept until the next chunk.
export class SseParser {
  private buffer = ''
  private type = ''
  private data: string[] = []
  private id: string | null = null

  push(chunk: string): SseEvent[] {
    this.buffer += chunk
    const events: SseEvent[] = []
    // Lines end with \n, \r\n or \r.
    const lines = this.buffer.split(/\r\n|\r|\n/)
    // The last element is an unfinished line (or "" after a final newline).
    this.buffer = lines.pop() ?? ''
    for (const line of lines) {
      const event = this.readLine(line)
      if (event) {
        events.push(event)
      }
    }
    return events
  }

  private readLine(line: string): SseEvent | null {
    if (line === '') {
      return this.dispatch()
    }
    if (line.startsWith(':')) {
      return null
    }
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }
    if (field === 'event') {
      this.type = value
    } else if (field === 'data') {
      this.data.push(value)
    } else if (field === 'id') {
      this.id = value
    }
    // Other fields (retry) are not used: the caller decides when to reconnect.
    return null
  }

  private dispatch(): SseEvent | null {
    const hasData = this.data.length > 0
    const event = { type: this.type || 'message', data: this.data.join('\n'), id: this.id }
    this.type = ''
    this.data = []
    this.id = null
    // An event with no data lines is not delivered (as in EventSource).
    return hasData ? event : null
  }
}

export interface ReadEventStreamOptions {
  headers: Record<string, string>
  signal: AbortSignal
  // Called once the server answered 200, before the first event.
  onOpen?: () => void
  onEvent: (event: SseEvent) => void
}

// Opens the stream and calls onEvent for each event until the server closes
// it (resolves) or the request fails (throws ApiError; status 0 = network).
// Aborting the signal stops it quietly (resolves).
export async function readEventStream(url: string, options: ReadEventStreamOptions): Promise<void> {
  let response: Response
  try {
    response = await fetch(url, {
      headers: { Accept: 'text/event-stream', ...options.headers },
      signal: options.signal,
      cache: 'no-store',
    })
  } catch (error) {
    if (options.signal.aborted) {
      return
    }
    throw new ApiError(error instanceof Error ? error.message : 'Stream failed', 0)
  }
  if (!response.ok || response.body === null) {
    throw new ApiError(await readErrorMessage(response), response.status)
  }
  options.onOpen?.()

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  const parser = new SseParser()
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        return
      }
      for (const event of parser.push(decoder.decode(value, { stream: true }))) {
        options.onEvent(event)
      }
    }
  } catch (error) {
    if (options.signal.aborted) {
      return
    }
    throw new ApiError(error instanceof Error ? error.message : 'Stream failed', 0)
  }
}
