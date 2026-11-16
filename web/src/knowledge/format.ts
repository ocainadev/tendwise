const DATE_TIME = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' })

// "16/11/2026, 14:05" from an ISO timestamp.
export function formatDateTime(iso: string): string {
  return DATE_TIME.format(new Date(iso))
}
