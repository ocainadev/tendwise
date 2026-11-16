import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError, type Language } from '../api/client'
import {
  getSettings,
  LANGUAGES,
  LIMITS,
  updateSettings,
  type AssistantSettings,
  type BusinessSettings,
  type WidgetSettings,
} from '../api/settings'
import { useMembership } from '../auth/session'
import { useToast } from '../components/Shell/Toast'
import { toFieldErrors, type FieldErrors } from './fieldErrors'
import { HoursEditor } from './HoursEditor'
import { Field, FieldErrorText, TextField } from './SettingsFields'
import './Settings.css'

// The colour and position the widget uses when the owner has not chosen yet.
const DEFAULT_COLOR = '#0f766e'
const DEFAULT_POSITION = 'bottom-right'

// IANA time zones the browser knows, offered as suggestions.
const TIME_ZONES: string[] = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : []

function settingsKey(slug: string) {
  return ['settings', slug]
}

// An empty optional text is sent as null ("not set"), so the assistant uses
// its default instead of an empty greeting.
function blankToNull(value: string | null): string | null {
  return value === null || value.trim() === '' ? null : value
}

function cleanUp(settings: BusinessSettings): BusinessSettings {
  const assistant = settings.assistantSettings
  return {
    ...settings,
    assistantSettings: {
      ...assistant,
      name: blankToNull(assistant.name),
      tone: blankToNull(assistant.tone),
      greeting: blankToNull(assistant.greeting),
      extraInstructions: blankToNull(assistant.extraInstructions),
      outOfHoursMessage: blankToNull(assistant.outOfHoursMessage),
    },
  }
}

function Section({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <section className="settings-section" aria-label={title}>
      <h2>{title}</h2>
      {description && <p className="knowledge-muted">{description}</p>}
      {children}
    </section>
  )
}

// /app/:slug/settings (OWNER only; RequireRole sends AGENT away and the API
// refuses them too). One form for every section and one save, because PUT
// replaces all the settings at once.
export function SettingsPage() {
  const { t } = useTranslation()
  const membership = useMembership()
  const slug = membership?.slug ?? ''
  const settings = useQuery({ queryKey: settingsKey(slug), queryFn: () => getSettings(slug) })

  if (settings.isPending) {
    return <p>{t('common.loading')}</p>
  }
  if (settings.isError) {
    return (
      <div className="shell-error" role="alert">
        <p>{settings.error instanceof ApiError && settings.error.status === 0 ? t('errors.network') : t('errors.generic')}</p>
        <button type="button" className="shell-button shell-button--secondary" onClick={() => void settings.refetch()}>
          {t('errors.retry')}
        </button>
      </div>
    )
  }
  // The form keeps its own copy; it starts from what the API returned.
  return <SettingsForm slug={slug} initial={settings.data} />
}

function SettingsForm({ slug, initial }: { slug: string; initial: BusinessSettings }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const { toast, showToast } = useToast()
  const [draft, setDraft] = useState(initial)
  const [errors, setErrors] = useState<FieldErrors>({})
  const [newDomain, setNewDomain] = useState('')

  const save = useMutation({
    mutationFn: (settings: BusinessSettings) => updateSettings(slug, settings),
    onSuccess: (saved) => {
      queryClient.setQueryData(settingsKey(slug), saved)
      setDraft(saved)
      setErrors({})
      showToast(t('settings.saved'))
    },
    onError: (error) => {
      const fieldErrors = error instanceof ApiError ? error.problem.errors : undefined
      setErrors(fieldErrors ? toFieldErrors(t, fieldErrors) : {})
    },
  })

  function setAssistant(changes: Partial<AssistantSettings>) {
    setDraft({ ...draft, assistantSettings: { ...draft.assistantSettings, ...changes } })
  }

  function setWidget(changes: Partial<WidgetSettings>) {
    setDraft({ ...draft, widgetSettings: { ...draft.widgetSettings, ...changes } })
  }

  function toggleLanguage(language: Language, enabled: boolean) {
    const current = draft.assistantSettings.languages
    const languages = enabled ? LANGUAGES.filter((l) => l === language || current.includes(l)) : current.filter((l) => l !== language)
    setAssistant({ languages })
  }

  function addDomain() {
    const domain = newDomain.trim()
    if (domain === '' || draft.widgetSettings.allowedDomains.includes(domain)) {
      return
    }
    setWidget({ allowedDomains: [...draft.widgetSettings.allowedDomains, domain] })
    setNewDomain('')
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    save.mutate(cleanUp(draft))
  }

  const assistant = draft.assistantSettings
  const widget = draft.widgetSettings
  const hasFieldErrors = Object.keys(errors).length > 0

  return (
    <form className="settings-form" onSubmit={handleSubmit}>
      <div>
        <h1>{t('settings.title')}</h1>
        <p className="knowledge-muted">{t('settings.description')}</p>
      </div>

      <Section title={t('settings.profile.title')}>
        <TextField
          label={t('settings.profile.name')}
          field="name"
          value={draft.name}
          onChange={(name) => setDraft({ ...draft, name })}
          errors={errors}
          maxLength={LIMITS.businessName}
          required
        />
        <TextField
          label={t('settings.profile.category')}
          field="category"
          value={draft.category}
          onChange={(category) => setDraft({ ...draft, category })}
          errors={errors}
          maxLength={LIMITS.category}
          hint={t('settings.profile.categoryHint')}
          required
        />
      </Section>

      <Section title={t('settings.hours.title')}>
        <Field label={t('settings.hours.timezone')} field="timezone" errors={errors} hint={t('settings.hours.timezoneHint')}>
          {(inputProps) => (
            <input
              {...inputProps}
              type="text"
              list="settings-time-zones"
              value={draft.timezone}
              required
              onChange={(event) => setDraft({ ...draft, timezone: event.target.value })}
            />
          )}
        </Field>
        <datalist id="settings-time-zones">
          {TIME_ZONES.map((zone) => (
            <option key={zone} value={zone} />
          ))}
        </datalist>
        <HoursEditor
          value={draft.openingHours}
          onChange={(openingHours) => setDraft({ ...draft, openingHours })}
          errors={errors}
        />
      </Section>

      <Section title={t('settings.languages.title')} description={t('settings.languages.description')}>
        <fieldset className="settings-languages">
          <legend>{t('settings.languages.enabled')}</legend>
          {LANGUAGES.map((language) => (
            <label key={language} className="settings-check">
              <input
                type="checkbox"
                checked={assistant.languages.includes(language)}
                onChange={(event) => toggleLanguage(language, event.target.checked)}
              />
              {t(`settings.languages.names.${language}`)}
            </label>
          ))}
          <FieldErrorText errors={errors} field="assistantSettings.languages" />
        </fieldset>
        <Field label={t('settings.languages.default')} field="defaultLanguage" errors={errors}>
          {(inputProps) => (
            <select
              {...inputProps}
              className="shell-select"
              value={draft.defaultLanguage}
              onChange={(event) => setDraft({ ...draft, defaultLanguage: event.target.value as Language })}
            >
              {LANGUAGES.map((language) => (
                <option key={language} value={language}>
                  {t(`settings.languages.names.${language}`)}
                </option>
              ))}
            </select>
          )}
        </Field>
      </Section>

      <Section title={t('settings.assistant.title')}>
        <TextField
          label={t('settings.assistant.name')}
          field="assistantSettings.name"
          value={assistant.name ?? ''}
          onChange={(name) => setAssistant({ name })}
          errors={errors}
          maxLength={LIMITS.assistantName}
          placeholder={t('settings.assistant.namePlaceholder')}
        />
        <TextField
          label={t('settings.assistant.tone')}
          field="assistantSettings.tone"
          value={assistant.tone ?? ''}
          onChange={(tone) => setAssistant({ tone })}
          errors={errors}
          maxLength={LIMITS.tone}
          placeholder={t('settings.assistant.tonePlaceholder')}
        />
        <TextField
          label={t('settings.assistant.greeting')}
          field="assistantSettings.greeting"
          value={assistant.greeting ?? ''}
          onChange={(greeting) => setAssistant({ greeting })}
          errors={errors}
          maxLength={LIMITS.greeting}
          placeholder={t('settings.assistant.greetingPlaceholder')}
          multiline
          showCounter
        />
        <TextField
          label={t('settings.assistant.extraInstructions')}
          field="assistantSettings.extraInstructions"
          value={assistant.extraInstructions ?? ''}
          onChange={(extraInstructions) => setAssistant({ extraInstructions })}
          errors={errors}
          maxLength={LIMITS.extraInstructions}
          hint={t('settings.assistant.extraInstructionsHint')}
          multiline
          showCounter
        />
      </Section>

      <Section title={t('settings.handoff.title')}>
        <Field
          label={t('settings.handoff.autoAckMinutes')}
          field="handoffSettings.autoAckMinutes"
          errors={errors}
          hint={t('settings.handoff.autoAckHint')}
        >
          {(inputProps) => (
            <input
              {...inputProps}
              type="number"
              min={LIMITS.autoAckMinMinutes}
              max={LIMITS.autoAckMaxMinutes}
              required
              value={draft.handoffSettings.autoAckMinutes}
              onChange={(event) => setDraft({ ...draft, handoffSettings: { autoAckMinutes: event.target.valueAsNumber } })}
            />
          )}
        </Field>
        <TextField
          label={t('settings.handoff.outOfHoursMessage')}
          field="assistantSettings.outOfHoursMessage"
          value={assistant.outOfHoursMessage ?? ''}
          onChange={(outOfHoursMessage) => setAssistant({ outOfHoursMessage })}
          errors={errors}
          maxLength={LIMITS.outOfHoursMessage}
          hint={t('settings.handoff.outOfHoursHint')}
          multiline
          showCounter
        />
      </Section>

      <Section title={t('settings.widget.title')}>
        <div className="settings-row">
          <Field label={t('settings.widget.color')} field="widgetSettings.color" errors={errors}>
            {(inputProps) => (
              <input
                {...inputProps}
                type="color"
                value={widget.color ?? DEFAULT_COLOR}
                onChange={(event) => setWidget({ color: event.target.value })}
              />
            )}
          </Field>
          <Field label={t('settings.widget.position')} field="widgetSettings.position" errors={errors}>
            {(inputProps) => (
              <select
                {...inputProps}
                className="shell-select"
                value={widget.position ?? DEFAULT_POSITION}
                onChange={(event) => setWidget({ position: event.target.value as WidgetSettings['position'] })}
              >
                <option value="bottom-right">{t('settings.widget.positions.bottom-right')}</option>
                <option value="bottom-left">{t('settings.widget.positions.bottom-left')}</option>
              </select>
            )}
          </Field>
        </div>
        <fieldset className="settings-domains">
          <legend>{t('settings.widget.domains')}</legend>
          <p className="knowledge-muted knowledge-small">{t('settings.widget.domainsHint')}</p>
          {widget.allowedDomains.length === 0 && <p className="knowledge-small">{t('settings.widget.domainsEmpty')}</p>}
          <ul className="settings-domain-list">
            {widget.allowedDomains.map((domain, index) => (
              <li key={domain}>
                <code>{domain}</code>
                <button
                  type="button"
                  className="shell-button shell-button--secondary"
                  aria-label={t('settings.widget.removeDomainLabel', { domain })}
                  onClick={() => setWidget({ allowedDomains: widget.allowedDomains.filter((d) => d !== domain) })}
                >
                  {t('settings.widget.removeDomain')}
                </button>
                <FieldErrorText errors={errors} field={`widgetSettings.allowedDomains[${index}]`} />
              </li>
            ))}
          </ul>
          <div className="settings-row">
            <input
              type="text"
              inputMode="url"
              aria-label={t('settings.widget.newDomain')}
              placeholder="https://www.minhaloja.com.br"
              value={newDomain}
              onChange={(event) => setNewDomain(event.target.value)}
              onKeyDown={(event) => {
                // Enter adds the domain instead of submitting the whole form.
                if (event.key === 'Enter') {
                  event.preventDefault()
                  addDomain()
                }
              }}
            />
            <button
              type="button"
              className="shell-button shell-button--secondary"
              disabled={widget.allowedDomains.length >= LIMITS.allowedDomains}
              onClick={addDomain}
            >
              {t('settings.widget.addDomain')}
            </button>
          </div>
          <FieldErrorText errors={errors} field="widgetSettings.allowedDomains" />
        </fieldset>
        {/* The install snippet comes with the widget bundle (T56). */}
        <p className="knowledge-muted knowledge-small">{t('settings.widget.snippetSoon')}</p>
      </Section>

      <div className="settings-save">
        {save.isError && (
          <p className="shell-error" role="alert">
            {hasFieldErrors
              ? t('settings.fixFields')
              : save.error instanceof ApiError && save.error.status === 0
                ? t('errors.network')
                : t('errors.generic')}
          </p>
        )}
        <button type="submit" className="shell-button" disabled={save.isPending}>
          {save.isPending ? t('settings.saving') : t('settings.save')}
        </button>
      </div>
      {toast}
    </form>
  )
}
