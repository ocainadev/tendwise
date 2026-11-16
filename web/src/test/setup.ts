import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'
// Same i18n set-up as the app (pt-BR texts), for every test.
import '../i18n'

// Unmount rendered components, drop fetch mocks and forget the demo's stored
// conversations after every test.
afterEach(() => {
  cleanup()
  window.sessionStorage.clear()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})
