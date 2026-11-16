// Client for the settings page (T53): GET and PUT /api/v1/panel/{slug}/settings.
// PUT replaces everything, so the page sends back the whole object it got,
// with its changes. A failed check answers 400 with problem.errors.

import type { Language } from './client'
import { apiJson } from './http'

export const DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const
export type Day = (typeof DAYS)[number]

export const LANGUAGES: Language[] = ['pt', 'en', 'es']

export interface TimeRange {
  open: string // HH:mm
  close: string // HH:mm; before open means it ends the next day
}

export interface AssistantSettings {
  name: string | null
  tone: string | null
  greeting: string | null
  extraInstructions: string | null
  languages: Language[]
  outOfHoursMessage: string | null
}

export interface WidgetSettings {
  color: string | null // #rrggbb
  position: 'bottom-right' | 'bottom-left' | null
  allowedDomains: string[]
}

export interface BusinessSettings {
  name: string
  category: string
  defaultLanguage: Language
  timezone: string
  // A day with [] is closed. {} means the hours are unknown (always open).
  openingHours: Partial<Record<Day, TimeRange[]>>
  assistantSettings: AssistantSettings
  handoffSettings: { autoAckMinutes: number }
  widgetSettings: WidgetSettings
}

// The same caps business-service checks (docs/architecture.md, T53).
export const LIMITS = {
  businessName: 120,
  category: 64,
  assistantName: 60,
  tone: 200,
  greeting: 500,
  extraInstructions: 1000,
  outOfHoursMessage: 500,
  intervalsPerDay: 4,
  allowedDomains: 20,
  autoAckMinMinutes: 1,
  autoAckMaxMinutes: 60,
}

function settingsPath(slug: string): string {
  return `/api/v1/panel/${encodeURIComponent(slug)}/settings`
}

export function getSettings(slug: string): Promise<BusinessSettings> {
  return apiJson<BusinessSettings>(settingsPath(slug))
}

export function updateSettings(slug: string, settings: BusinessSettings): Promise<BusinessSettings> {
  return apiJson<BusinessSettings>(settingsPath(slug), {
    method: 'PUT',
    body: JSON.stringify(settings),
  })
}
