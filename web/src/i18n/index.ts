import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import { ptBR } from './pt-BR'

// The panel languages. Only pt-BR has texts today; en and es are listed so
// that adding them is one new file each (see ./pt-BR.ts).
export const SUPPORTED_LANGUAGES = ['pt-BR', 'en', 'es'] as const

// Imported once (main.tsx, test setup) before anything renders. The texts are
// bundled, so initialisation is synchronous and no component has to wait.
void i18n.use(initReactI18next).init({
  resources: {
    'pt-BR': { translation: ptBR },
  },
  lng: 'pt-BR',
  fallbackLng: 'pt-BR',
  supportedLngs: SUPPORTED_LANGUAGES,
  initAsync: false,
  interpolation: {
    // React already escapes what it renders.
    escapeValue: false,
  },
})

// Screen readers and the browser's spell checker follow <html lang>.
document.documentElement.lang = i18n.language

export default i18n
