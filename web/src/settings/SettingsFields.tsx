import { useId, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import type { FieldErrors } from './fieldErrors'

// An id for the error text of a field path such as "openingHours.mon[0]".
export function errorId(field: string): string {
  return `error-${field.replace(/[^a-zA-Z0-9]/g, '-')}`
}

// The error the API gave for one field, if any, right under that field.
export function FieldErrorText({ errors, field }: { errors: FieldErrors; field: string }) {
  const text = errors[field]
  if (!text) {
    return null
  }
  return (
    <span className="settings-field-error" id={errorId(field)}>
      {text}
    </span>
  )
}

// What a Field passes to its input: link it to the label, the hint and the error.
export interface InputProps {
  id: string
  'aria-describedby'?: string
  'aria-invalid'?: true
}

interface FieldProps {
  label: string
  // The API's field path, to find its error.
  field: string
  errors: FieldErrors
  hint?: string
  // Extra text under the input, e.g. a character counter.
  extra?: ReactNode
  children: (inputProps: InputProps) => ReactNode
}

// A label, one input (given by `children`), its hint and the API's error.
// The hint and the error are linked with aria-describedby, so the label
// itself stays just the field's name.
export function Field({ label, field, errors, hint, extra, children }: FieldProps) {
  const id = useId()
  const hintId = `${id}-hint`
  const hasError = Boolean(errors[field])
  const describedBy = [hint ? hintId : null, hasError ? errorId(field) : null].filter(Boolean).join(' ')

  return (
    <div className="settings-field">
      <label htmlFor={id}>{label}</label>
      {children({
        id,
        'aria-describedby': describedBy || undefined,
        'aria-invalid': hasError ? true : undefined,
      })}
      {hint && (
        <span className="knowledge-small" id={hintId}>
          {hint}
        </span>
      )}
      {extra}
      <FieldErrorText errors={errors} field={field} />
    </div>
  )
}

interface TextFieldProps {
  label: string
  field: string
  value: string
  onChange: (value: string) => void
  errors: FieldErrors
  maxLength: number
  hint?: string
  placeholder?: string
  required?: boolean
  multiline?: boolean
  // Shows "N of max characters" under the field as the user types.
  showCounter?: boolean
}

// A text input or textarea in a Field, with an optional character counter.
export function TextField({ value, onChange, maxLength, placeholder, required, multiline, showCounter, ...field }: TextFieldProps) {
  const { t } = useTranslation()
  const counter = showCounter && (
    <span className="knowledge-small">{t('settings.characters', { count: value.length, max: maxLength })}</span>
  )

  return (
    <Field {...field} extra={counter}>
      {(inputProps) => {
        const props = { ...inputProps, value, maxLength, placeholder, required }
        return multiline ? (
          <textarea rows={4} {...props} onChange={(event) => onChange(event.target.value)} />
        ) : (
          <input type="text" {...props} onChange={(event) => onChange(event.target.value)} />
        )
      }}
    </Field>
  )
}
