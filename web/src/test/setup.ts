import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'
// Same i18n set-up as the app (pt-BR texts), for every test.
import '../i18n'

// Unmount rendered components and drop fetch mocks after every test.
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})
