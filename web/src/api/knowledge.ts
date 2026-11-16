// Client for the knowledge base routes of the panel, served by agent-service
// (T50, T51): /api/v1/panel/{slug}/documents… and /api/v1/panel/{slug}/playground.
// They use the same session cookies and CSRF header as business-service, so
// every call goes through apiFetch/apiJson.

import { ApiError } from './client'
import { apiFetch, apiJson } from './http'

export type DocumentStatus = 'PENDING' | 'INDEXED' | 'FAILED'

// FILE: seeded from data/businesses; EDITOR: written in the panel.
export type DocumentSource = 'FILE' | 'EDITOR'

export interface DocumentSummary {
  id: number
  sourcePath: string
  title: string
  source: DocumentSource
  status: DocumentStatus
  characters: number
  updatedAt: string
  updatedBy: number | null
}

export interface DocumentDetail extends DocumentSummary {
  content: string
}

export interface DocumentInput {
  title: string
  content: string
}

export interface PlaygroundSource {
  title: string
  sourcePath: string
  section: string | null
}

export interface PlaygroundAnswer {
  conversationId: string
  reply: string
  sources: PlaygroundSource[]
  // True when a real chat would have been handed to a person.
  handoff: boolean
}

// The same limits agent-service checks (app/rag/documents.py).
export const MAX_TITLE_CHARACTERS = 200
export const MAX_DOCUMENT_CHARACTERS = 50_000

function documentsPath(slug: string): string {
  return `/api/v1/panel/${encodeURIComponent(slug)}/documents`
}

export function listDocuments(slug: string): Promise<DocumentSummary[]> {
  return apiJson<DocumentSummary[]>(documentsPath(slug))
}

export function getDocument(slug: string, id: number): Promise<DocumentDetail> {
  return apiJson<DocumentDetail>(`${documentsPath(slug)}/${id}`)
}

export function createDocument(slug: string, input: DocumentInput): Promise<DocumentDetail> {
  return apiJson<DocumentDetail>(documentsPath(slug), {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export function updateDocument(
  slug: string,
  id: number,
  input: DocumentInput,
): Promise<DocumentDetail> {
  return apiJson<DocumentDetail>(`${documentsPath(slug)}/${id}`, {
    method: 'PUT',
    body: JSON.stringify(input),
  })
}

// 204 No Content: there is no body to parse, so apiJson cannot be used.
export async function deleteDocument(slug: string, id: number): Promise<void> {
  const response = await apiFetch(`${documentsPath(slug)}/${id}`, { method: 'DELETE' })
  if (!response.ok) {
    throw new ApiError(`HTTP ${response.status}`, response.status)
  }
}

export function reindexDocuments(slug: string): Promise<{ queued: number }> {
  return apiJson<{ queued: number }>(`${documentsPath(slug)}/reindex`, { method: 'POST' })
}

// null conversationId starts a new test conversation.
export function askPlayground(
  slug: string,
  conversationId: string | null,
  message: string,
): Promise<PlaygroundAnswer> {
  return apiJson<PlaygroundAnswer>(`/api/v1/panel/${encodeURIComponent(slug)}/playground`, {
    method: 'POST',
    body: JSON.stringify({ conversationId, message }),
  })
}
