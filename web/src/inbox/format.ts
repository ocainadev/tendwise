import { useEffect, useState } from 'react'

const TIME = new Intl.DateTimeFormat('pt-BR', { timeStyle: 'short' })
const DATE_TIME = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short' })

// "14:05" for a time today, "15/11/2026, 14:05" for an older one.
export function formatMessageTime(iso: string, now: Date = new Date()): string {
  const date = new Date(iso)
  return date.toDateString() === now.toDateString() ? TIME.format(date) : DATE_TIME.format(date)
}

// How long since `iso`: "04:12" (minutes:seconds) or "1:04:12" past an hour.
export function formatElapsed(iso: string, now: number): string {
  const seconds = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 1000))
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const rest = seconds % 60
  const pad = (value: number) => String(value).padStart(2, '0')
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(rest)}` : `${pad(minutes)}:${pad(rest)}`
}

// The current time in milliseconds, updated every second, for waiting timers.
export function useNow(): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  return now
}

// The API has no customer name yet, so a conversation is shown as an
// anonymous customer with the start of its id, to tell rows apart.
export function shortId(id: string): string {
  return id.slice(0, 4).toUpperCase()
}
