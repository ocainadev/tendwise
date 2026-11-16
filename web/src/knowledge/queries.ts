// TanStack Query hooks for the knowledge page. A saved document is PENDING
// until agent-service's job worker indexes it (a few seconds), so the list and
// the open document poll while anything is still PENDING.

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../api/client'
import {
  createDocument,
  deleteDocument,
  getDocument,
  listDocuments,
  reindexDocuments,
  updateDocument,
  type DocumentInput,
  type DocumentStatus,
} from '../api/knowledge'

// How often to ask again while a document is PENDING.
export const POLL_INTERVAL_MS = 2000

function documentsKey(slug: string) {
  return ['knowledge', slug, 'documents']
}

function documentKey(slug: string, id: number) {
  return ['knowledge', slug, 'document', id]
}

function pollWhilePending(statuses: DocumentStatus[] | undefined): number | false {
  return statuses?.includes('PENDING') ? POLL_INTERVAL_MS : false
}

export function useDocuments(slug: string) {
  return useQuery({
    queryKey: documentsKey(slug),
    queryFn: () => listDocuments(slug),
    refetchInterval: (query) => pollWhilePending(query.state.data?.map((d) => d.status)),
  })
}

export function useDocument(slug: string, id: number | null) {
  return useQuery({
    queryKey: documentKey(slug, id ?? 0),
    queryFn: () => getDocument(slug, id ?? 0),
    enabled: id !== null,
    refetchInterval: (query) => pollWhilePending(query.state.data && [query.state.data.status]),
  })
}

// Create (id null) or update a document; afterwards the list and the
// document are fetched again, so their PENDING status starts being polled.
export function useSaveDocument(slug: string, id: number | null) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: DocumentInput) =>
      id === null ? createDocument(slug, input) : updateDocument(slug, id, input),
    onSuccess: (saved) => {
      queryClient.setQueryData(documentKey(slug, saved.id), saved)
      void queryClient.invalidateQueries({ queryKey: documentsKey(slug) })
    },
  })
}

export function useDeleteDocument(slug: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => deleteDocument(slug, id),
    onSuccess: (_, id) => {
      queryClient.removeQueries({ queryKey: documentKey(slug, id) })
      void queryClient.invalidateQueries({ queryKey: documentsKey(slug) })
    },
  })
}

export function useReindex(slug: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => reindexDocuments(slug),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: documentsKey(slug) }),
  })
}

// A friendly, translated message for a failed request.
export function useErrorMessage(): (error: unknown) => string {
  const { t } = useTranslation()
  return (error) => {
    if (error instanceof ApiError) {
      if (error.status === 0) return t('errors.network')
      if (error.status === 404) return t('knowledge.errors.notFound')
      if (error.status === 422) return t('knowledge.errors.tooLarge')
      if (error.status === 429) return t('knowledge.errors.tooManyQuestions')
      if (error.status === 403) return t('knowledge.errors.forbidden')
      if (error.status >= 500) return t('knowledge.errors.unavailable')
    }
    return t('errors.generic')
  }
}
