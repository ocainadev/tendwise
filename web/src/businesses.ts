import type { Language } from './api/client'

// The two demo businesses seeded by business-service.
// The MVP has no "list businesses" endpoint, so they are hard-coded here.
// The slugs and publishable keys must match the seed (business-service
// V2__seed.sql and V5__widget.sql) and data/businesses/.

export interface Business {
  slug: string
  name: string
  // Starts a conversation on the public API. Public by design, like the key a
  // website puts in the widget snippet.
  publishableKey: string
  category: string
  language: Language
  exampleQuestions: string[]
}

export const BUSINESSES: Business[] = [
  {
    slug: 'joes-pizza',
    name: "Joe's Pizza",
    publishableKey: 'pk_demo_joes_pizza',
    category: 'Restaurant',
    language: 'en',
    exampleQuestions: [
      'Do you have gluten-free pizza?',
      'What time do you close on Friday?',
      'Where is my order JP-1042? Last digits 0102',
    ],
  },
  {
    slug: 'denim-corner',
    name: 'Denim Corner',
    publishableKey: 'pk_demo_denim_corner',
    category: 'Clothing store',
    language: 'pt',
    exampleQuestions: [
      'Vocês fazem ajuste de barra?',
      'Qual o horário de sábado?',
      'Cadê meu pedido DC-2003? Final 0203',
    ],
  },
]
