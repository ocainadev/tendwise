// Client for agent-service's chat endpoint (POST /api/v1/chat).
// The URL is relative: in development Vite proxies /api to agent-service,
// and in Docker nginx does the same, so the browser never needs CORS.

export type Intent =
  | 'catalog_question'
  | 'order_status'
  | 'complaint'
  | 'human_request'
  | 'other'

export type Language = 'en' | 'es' | 'pt'

export interface Source {
  title: string
  source_path: string
  section: string | null
}

export interface ChatRequest {
  // null starts a new conversation; afterwards send back the id we received.
  conversation_id: string | null
  business_slug: string
  message: string
}

export interface ChatResponse {
  conversation_id: string
  reply: string
  intent: Intent
  language: Language
  sources: Source[]
  handoff: boolean
}

// Thrown for network failures and non-2xx responses. `status` is 0 when the
// server could not be reached at all.
export class ApiError extends Error {
  status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export async function sendMessage(request: ChatRequest): Promise<ChatResponse> {
  let response: Response
  try {
    response = await fetch('/api/v1/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    })
  } catch {
    throw new ApiError('Could not reach the server. Check your connection and try again.', 0)
  }

  if (!response.ok) {
    throw new ApiError(await readErrorMessage(response), response.status)
  }

  return (await response.json()) as ChatResponse
}

// FastAPI sends errors as {"detail": "..."}; validation errors (422) send
// {"detail": [{"msg": "..."}, ...]}. Fall back to a generic message.
async function readErrorMessage(response: Response): Promise<string> {
  try {
    const body: unknown = await response.json()
    if (typeof body === 'object' && body !== null && 'detail' in body) {
      const detail = body.detail
      if (typeof detail === 'string' && detail !== '') {
        return detail
      }
      if (Array.isArray(detail) && typeof detail[0]?.msg === 'string') {
        return detail[0].msg
      }
    }
  } catch {
    // The body was not JSON: use the fallback below.
  }

  if (response.status === 429) {
    return 'Too many messages. Please wait a moment and try again.'
  }
  // 502/503/504 come from the proxy (Vite or nginx) when agent-service is down.
  if (response.status >= 502 && response.status <= 504) {
    return 'The chat service is not available right now. Please try again in a moment.'
  }
  return `Something went wrong (HTTP ${response.status}). Please try again.`
}
