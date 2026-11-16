import { useEffect, useState } from 'react'
import './Shell.css'

// How long a toast stays on screen.
export const TOAST_MS = 4000

// A short success message in the corner of the screen, e.g. "Saved".
// `showToast(text)` replaces the current one; it hides itself after TOAST_MS.
export function useToast() {
  const [text, setText] = useState<string | null>(null)

  useEffect(() => {
    if (text === null) {
      return
    }
    const timer = setTimeout(() => setText(null), TOAST_MS)
    return () => clearTimeout(timer)
  }, [text])

  const toast = text ? (
    <div className="shell-toast" role="status">
      {text}
    </div>
  ) : null

  return { toast, showToast: setText }
}
