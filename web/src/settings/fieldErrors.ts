// Turns the field errors of a 400 from PUT /settings into pt-BR texts, keyed
// by the field path the API uses (e.g. "openingHours.mon[0].open"). The API's
// own messages are in English, so the text is chosen from the field and the
// kind of message instead.

import type { TFunction } from 'i18next'
import type { FieldError } from '../api/client'

export type FieldErrors = Record<string, string>

function textFor(t: TFunction, { field, message }: FieldError): string {
  if (/^openingHours\.\w+\[\d+\]\.(open|close)$/.test(field)) return t('settings.errors.time')
  if (/^openingHours\.\w+\[\d+\]$/.test(field)) return t('settings.errors.sameTime')
  if (/^openingHours\.\w+$/.test(field)) return t('settings.errors.tooManyIntervals')
  if (field === 'timezone') return t('settings.errors.timezone')
  if (field === 'defaultLanguage') return t('settings.errors.defaultLanguage')
  if (field.startsWith('assistantSettings.languages')) return t('settings.errors.languages')
  if (field === 'handoffSettings.autoAckMinutes') return t('settings.errors.autoAck')
  if (field === 'widgetSettings.color') return t('settings.errors.color')
  if (field === 'widgetSettings.allowedDomains') return t('settings.errors.tooManyDomains')
  if (field.startsWith('widgetSettings.allowedDomains[')) return t('settings.errors.domain')
  if (message.startsWith('must not be blank')) return t('settings.errors.required')
  return t('settings.errors.invalid')
}

export function toFieldErrors(t: TFunction, errors: FieldError[]): FieldErrors {
  const result: FieldErrors = {}
  for (const error of errors) {
    // Keep the first problem of each field.
    result[error.field] ??= textFor(t, error)
  }
  return result
}
