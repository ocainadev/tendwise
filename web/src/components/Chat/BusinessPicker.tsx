import type { Business } from '../../businesses'

interface BusinessPickerProps {
  businesses: Business[]
  value: string
  onChange: (slug: string) => void
  disabled: boolean
}

export function BusinessPicker({ businesses, value, onChange, disabled }: BusinessPickerProps) {
  return (
    <div className="business-picker">
      <label htmlFor="business-picker">Business</label>
      <select
        id="business-picker"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        disabled={disabled}
      >
        {businesses.map((business) => (
          <option key={business.slug} value={business.slug}>
            {business.name} · {business.category}
          </option>
        ))}
      </select>
    </div>
  )
}
