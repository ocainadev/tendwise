import type { Language } from './api/client'

// The two demo businesses seeded by business-service.
// The MVP has no "list businesses" endpoint, so they are hard-coded here.
// The slugs must match the seed (business-service) and data/businesses/.

export interface Business {
  slug: string
  name: string
  category: string
  language: Language
  exampleQuestions: string[]
}

export const BUSINESSES: Business[] = [
  {
    slug: 'joes-pizza',
    name: "Joe's Pizza",
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
    category: 'Clothing store',
    language: 'pt',
    exampleQuestions: [
      'Vocês fazem ajuste de barra?',
      'Qual o horário de sábado?',
      'Cadê meu pedido DC-2003? Final 0203',
    ],
  },
]
