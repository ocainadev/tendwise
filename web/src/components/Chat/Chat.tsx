import { useState } from 'react'
import { BUSINESSES } from '../../businesses'
import { BusinessPicker } from './BusinessPicker'
import { ConversationView } from './ConversationView'
import './Chat.css'

// The demo page: a business picker and that business's conversation. Each
// business has its own conversation (kept in sessionStorage), so switching
// back and forth continues where it was.
export function Chat() {
  const [businessSlug, setBusinessSlug] = useState(BUSINESSES[0].slug)
  const business = BUSINESSES.find((b) => b.slug === businessSlug) ?? BUSINESSES[0]

  return (
    <div className="chat">
      <header className="chat__header">
        <div>
          <h1 className="chat__title">Tendwise</h1>
          <p className="chat__subtitle">AI customer-service clerk · demo</p>
        </div>
        <BusinessPicker businesses={BUSINESSES} value={business.slug} onChange={setBusinessSlug} />
      </header>

      {/* key: another business mounts a fresh view with its own state and stream. */}
      <ConversationView key={business.slug} business={business} />
    </div>
  )
}
