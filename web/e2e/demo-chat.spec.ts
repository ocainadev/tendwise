import { expect, test } from '@playwright/test'

// T42: the demo chat on the public API and SSE. "The AI conversation works as
// before": a question gets an AI reply with its sources, the conversation
// survives a page refresh, and asking for a person shows the handoff notice.
// The answers come from the fake LLM (./fake-llm/server.mjs).

const FAKE_ANSWER = 'Resposta de teste do LLM falso.'

test('an AI conversation works as before, also after a refresh', async ({ page }) => {
  await page.goto('/demo')
  const input = page.getByLabel('Message')

  await input.fill('Do you have gluten-free pizza?')
  await input.press('Enter')

  await expect(page.getByText('Do you have gluten-free pizza?', { exact: true })).toBeVisible()
  // The greeting of the new conversation, then the AI reply from the event stream.
  await expect(page.getByText(/Welcome to Joe's Pizza/)).toBeVisible()
  await expect(page.getByText(FAKE_ANSWER)).toBeVisible()
  await expect(page.getByText(/^Sources \(\d+\)$/)).toBeVisible()
  await expect(page.getByText("Joe's Pizza is typing…")).toBeHidden()

  // The input was never blocked: a second message right away.
  await input.fill('What time do you close on Friday?')
  await input.press('Enter')
  await expect(page.getByText(FAKE_ANSWER)).toHaveCount(2)

  // A refresh continues the same conversation (sessionStorage + history).
  await page.reload()
  await expect(page.getByText('Do you have gluten-free pizza?', { exact: true })).toBeVisible()
  await expect(page.getByText(FAKE_ANSWER)).toHaveCount(2)
})

test('asking for a person shows the handoff notice, in Portuguese', async ({ page }) => {
  await page.goto('/demo')
  await page.getByLabel('Business').selectOption('denim-corner')
  const input = page.getByLabel('Message')

  await input.fill('Quero falar com uma pessoa')
  await input.press('Enter')

  await expect(page.getByText('Quero falar com uma pessoa', { exact: true })).toBeVisible()
  await expect(page.getByText('Uma pessoa da equipe vai continuar esta conversa.')).toBeVisible()
  await expect(page.getByText('Denim Corner está digitando…')).toBeHidden()
})
