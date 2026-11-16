// Texts the demo shows inside the conversation, in the business's language.
// Portuguese comes first (product priority); the rest of the demo page stays
// in English until the widget replaces it (T56).

import type { Language } from '../../api/client'
import type { PublicMessage } from '../../api/publicApi'

export interface ConversationTexts {
  typing: (name: string) => string
  joined: (name: string | null) => string
  released: string
  autoAck: string
  aiFailed: string
  handoff: string
  sources: (count: number) => string
  you: string
}

const PT: ConversationTexts = {
  typing: (name) => `${name} está digitando…`,
  joined: (name) => (name ? `${name} entrou na conversa` : 'Uma pessoa da equipe entrou na conversa'),
  released: 'Você voltou para o assistente',
  autoAck: 'Já avisei a equipe. Alguém vai responder aqui assim que possível.',
  aiFailed: 'O assistente não conseguiu responder. Uma pessoa da equipe vai responder aqui.',
  handoff: 'Uma pessoa da equipe vai continuar esta conversa.',
  sources: (count) => `Fontes (${count})`,
  you: 'Você:',
}

const EN: ConversationTexts = {
  typing: (name) => `${name} is typing…`,
  joined: (name) => (name ? `${name} joined the conversation` : 'A team member joined the conversation'),
  released: 'You are back with the assistant',
  autoAck: 'The team has been notified and will reply here as soon as possible.',
  aiFailed: 'The assistant could not answer. A team member will reply here.',
  handoff: 'A team member will continue this conversation.',
  sources: (count) => `Sources (${count})`,
  you: 'You:',
}

export function textsFor(language: Language): ConversationTexts {
  return language === 'pt' ? PT : EN
}

// What a SYSTEM message says. Known events get the translated text; anything
// else (a future event) shows the server's English text as is.
export function systemText(message: PublicMessage, texts: ConversationTexts): string {
  switch (message.event) {
    case 'TAKEN_OVER':
      return texts.joined(message.authorName ?? null)
    case 'RELEASED':
      return texts.released
    case 'AUTO_ACK':
      return texts.autoAck
    case 'AI_FAILED':
      return texts.aiFailed
    default:
      return message.body
  }
}
