import { useTranslation } from 'react-i18next'
import { DAYS, LIMITS, type BusinessSettings, type Day, type TimeRange } from '../api/settings'
import { FieldErrorText } from './SettingsFields'
import type { FieldErrors } from './fieldErrors'

type OpeningHours = BusinessSettings['openingHours']

// The interval a day gets when it is opened, or a new interval is added.
const NEW_INTERVAL: TimeRange = { open: '09:00', close: '18:00' }

// Shown when the owner starts filling in hours that were never set:
// Monday to Friday 09:00–18:00, the weekend closed.
function defaultWeek(): OpeningHours {
  const week: OpeningHours = {}
  for (const day of DAYS) {
    week[day] = day === 'sat' || day === 'sun' ? [] : [{ ...NEW_INTERVAL }]
  }
  return week
}

interface Props {
  value: OpeningHours
  onChange: (value: OpeningHours) => void
  errors: FieldErrors
}

// Weekly opening hours: each day is closed or has 1 to 4 intervals.
export function HoursEditor({ value, onChange, errors }: Props) {
  const { t } = useTranslation()

  // {} means the hours are unknown and the business counts as always open,
  // which is different from "closed every day".
  if (Object.keys(value).length === 0) {
    return (
      <div className="settings-hours-unknown">
        <p className="knowledge-muted">{t('settings.hours.unknown')}</p>
        <button type="button" className="shell-button shell-button--secondary" onClick={() => onChange(defaultWeek())}>
          {t('settings.hours.setHours')}
        </button>
      </div>
    )
  }

  function setDay(day: Day, ranges: TimeRange[]) {
    // Every day is sent, so a day missing from the API's answer becomes closed.
    const week: OpeningHours = {}
    for (const other of DAYS) {
      week[other] = other === day ? ranges : (value[other] ?? [])
    }
    onChange(week)
  }

  return (
    <div className="settings-hours">
      {DAYS.map((day) => {
        const ranges = value[day] ?? []
        const dayName = t(`settings.hours.days.${day}`)
        const closed = ranges.length === 0

        function setRange(index: number, range: TimeRange) {
          setDay(day, ranges.map((old, i) => (i === index ? range : old)))
        }

        return (
          <fieldset key={day} className="settings-day">
            <legend>{dayName}</legend>
            <label className="settings-check">
              <input
                type="checkbox"
                checked={closed}
                onChange={(event) => setDay(day, event.target.checked ? [] : [{ ...NEW_INTERVAL }])}
              />
              {t('settings.hours.closed')}
            </label>
            {ranges.map((range, index) => {
              const field = `openingHours.${day}[${index}]`
              const labels = { day: dayName, index: index + 1 }
              return (
                <div key={index} className="settings-interval">
                  <input
                    type="time"
                    aria-label={t('settings.hours.openLabel', labels)}
                    value={range.open}
                    required
                    onChange={(event) => setRange(index, { ...range, open: event.target.value })}
                  />
                  <span aria-hidden="true">–</span>
                  <input
                    type="time"
                    aria-label={t('settings.hours.closeLabel', labels)}
                    value={range.close}
                    required
                    onChange={(event) => setRange(index, { ...range, close: event.target.value })}
                  />
                  <button
                    type="button"
                    className="shell-button shell-button--secondary"
                    aria-label={t('settings.hours.removeIntervalLabel', labels)}
                    onClick={() => setDay(day, ranges.filter((_, i) => i !== index))}
                  >
                    {t('settings.hours.removeInterval')}
                  </button>
                  <FieldErrorText errors={errors} field={`${field}.open`} />
                  <FieldErrorText errors={errors} field={`${field}.close`} />
                  <FieldErrorText errors={errors} field={field} />
                </div>
              )
            })}
            {!closed && (
              <button
                type="button"
                className="shell-button shell-button--secondary settings-add-interval"
                aria-label={t('settings.hours.addIntervalLabel', { day: dayName })}
                disabled={ranges.length >= LIMITS.intervalsPerDay}
                onClick={() => setDay(day, [...ranges, { ...NEW_INTERVAL }])}
              >
                {t('settings.hours.addInterval')}
              </button>
            )}
            <FieldErrorText errors={errors} field={`openingHours.${day}`} />
          </fieldset>
        )
      })}
      <p className="knowledge-muted knowledge-small">{t('settings.hours.overnight')}</p>
    </div>
  )
}
