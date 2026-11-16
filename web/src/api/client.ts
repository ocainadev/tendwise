// Shared API types. The demo chat calls business-service's public API
// (./publicApi.ts); the panels call the cookie-authenticated routes (./http.ts).

export type Language = 'en' | 'es' | 'pt'

// One invalid field of a 400 problem, e.g. {field: "openingHours.mon[0].open"}.
export interface FieldError {
  field: string
  message: string
}

// The parts of a business-service RFC 7807 problem the UI reads. `title`
// tells errors with the same status apart (e.g. "Last owner").
export interface Problem {
  title?: string
  errors?: FieldError[]
}

// Thrown for network failures and non-2xx responses. `status` is 0 when the
// server could not be reached at all; `problem` is the parsed error body, when
// there was one.
export class ApiError extends Error {
  status: number
  problem: Problem

  constructor(message: string, status: number, problem: Problem = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}
