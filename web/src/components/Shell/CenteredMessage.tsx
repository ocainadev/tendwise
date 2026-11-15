import type { ReactNode } from 'react'
import './Shell.css'

// A short message in the middle of the screen (loading, errors).
export function CenteredMessage({ children }: { children: ReactNode }) {
  return <div className="shell-centered">{children}</div>
}
