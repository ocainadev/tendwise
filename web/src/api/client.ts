// Shared API types. The demo chat calls business-service's public API
// (./publicApi.ts); the panels call the cookie-authenticated routes (./http.ts).

export type Language = 'en' | 'es' | 'pt'

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
