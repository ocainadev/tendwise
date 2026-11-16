import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { BUSINESSES } from '../../businesses'
import { CONVERSATION_ID, installFakePublicApi, TOKEN } from '../../test/fakePublicApi'
import { Chat } from './Chat'
import { ConversationView } from './ConversationView'

const BASE = '/public/v1/conversations'
const MESSAGES = `${BASE}/${CONVERSATION_ID}/messages`
const DENIM = BUSINESSES.find((business) => business.slug === 'denim-corner')!

function setup() {
  const user = userEvent.setup()
  render(<Chat />)
  return {
    user,
    input: screen.getByLabelText('Message'),
    sendButton: screen.getByRole('button', { name: 'Send' }),
    picker: screen.getByLabelText('Business'),
  }
}

describe('Chat on the public API', () => {
  it('starts a conversation with the publishable key and sends the message with the token', async () => {
    const api = installFakePublicApi()
    const { user, input } = setup()

    await user.type(input, 'Do you have gluten-free pizza?{Enter}')

    expect(await screen.findByText('Do you have gluten-free pizza?')).toBeInTheDocument()
    expect(screen.getByText('Hi! How can I help you today?')).toBeInTheDocument()
    expect(api.requestsTo('POST', BASE)[0].body).toEqual({
      publishableKey: 'pk_demo_joes_pizza',
      language: 'en',
    })
    const sent = api.requestsTo('POST', MESSAGES)
    expect(sent).toHaveLength(1)
    expect(sent[0].body).toEqual({ body: 'Do you have gluten-free pizza?' })
    expect(sent[0].headers.get('Authorization')).toBe(`Bearer ${TOKEN}`)
    // The token never goes in a URL.
    expect(api.requests.every((request) => !request.url.includes(TOKEN))).toBe(true)
    expect(input).toHaveValue('')
    expect(input).toHaveFocus()
  })

  it('shows "typing…" until the AI reply arrives on the event stream', async () => {
    const api = installFakePublicApi()
    const { user, input } = setup()

    await user.type(input, 'Do you have gluten-free pizza?{Enter}')
    expect(await screen.findByText("Joe's Pizza is typing…")).toBeInTheDocument()

    await waitFor(() => expect(api.openStream()).toBeDefined())
    expect(api.openStream()!.headers.get('Authorization')).toBe(`Bearer ${TOKEN}`)
    api.reply('AI', 'Yes, our gluten-free base costs $2 extra.')

    expect(await screen.findByText('Yes, our gluten-free base costs $2 extra.')).toBeInTheDocument()
    expect(screen.queryByText("Joe's Pizza is typing…")).not.toBeInTheDocument()
  })

  it('shows "typing…" when the server sends ai.typing', async () => {
    const api = installFakePublicApi()
    const { user, input } = setup()
    await user.type(input, 'Hello{Enter}')
    await waitFor(() => expect(api.openStream()).toBeDefined())
    api.reply('AI', 'Hi!')
    await screen.findByText('Hi!')
    expect(screen.queryByText("Joe's Pizza is typing…")).not.toBeInTheDocument()

    api.emit('ai.typing', { type: 'ai.typing' })

    expect(await screen.findByText("Joe's Pizza is typing…")).toBeInTheDocument()
  })

  it('keeps the input enabled while a reply is pending, so Enter sends the next message', async () => {
    const api = installFakePublicApi()
    const { user, input, sendButton } = setup()

    await user.type(input, 'First question{Enter}')
    await screen.findByText('First question')
    // No reply yet: the next message is sent on its own, not merged into the input.
    await user.type(input, 'Second question{Enter}')
    await screen.findByText('Second question')

    expect(api.requestsTo('POST', MESSAGES).map((request) => request.body)).toEqual([
      { body: 'First question' },
      { body: 'Second question' },
    ])
    // Only one conversation was started.
    expect(api.requestsTo('POST', BASE)).toHaveLength(1)
    expect(input).toHaveValue('')
    expect(input).toBeEnabled()
    await user.type(input, 'x')
    expect(sendButton).toBeEnabled()
  })

  it('sends an example question when its chip is clicked', async () => {
    const api = installFakePublicApi()
    const { user } = setup()

    await user.click(screen.getByRole('button', { name: 'What time do you close on Friday?' }))

    await screen.findByText('What time do you close on Friday?', { selector: 'p' })
    expect(api.requestsTo('POST', MESSAGES)[0].body).toEqual({ body: 'What time do you close on Friday?' })
    // The chips are only for an empty conversation.
    expect(screen.queryByRole('button', { name: 'Do you have gluten-free pizza?' })).not.toBeInTheDocument()
  })

  it('shows the sources under an AI reply', async () => {
    const api = installFakePublicApi()
    const { user, input } = setup()
    await user.type(input, 'Gluten-free?{Enter}')
    await waitFor(() => expect(api.openStream()).toBeDefined())

    api.reply('AI', 'Yes.', {
      sources: [
        { title: 'Menu', section: 'Pizzas' },
        { title: 'FAQ', section: null },
      ],
    })

    expect(await screen.findByText('Sources (2)')).toBeInTheDocument()
    expect(screen.getByText('Menu – Pizzas')).toBeInTheDocument()
    expect(screen.getByText('FAQ')).toBeInTheDocument()
  })

  it('shows staff messages with the staff name and the system events, in Portuguese', async () => {
    const api = installFakePublicApi('Olá! Como posso ajudar?')
    const { user, input, picker } = setup()
    await user.selectOptions(picker, 'denim-corner')
    await user.type(screen.getByLabelText('Message'), 'Quero falar com uma pessoa{Enter}')
    await waitFor(() => expect(api.openStream()).toBeDefined())
    expect(api.requestsTo('POST', BASE)[0].body).toEqual({
      publishableKey: 'pk_demo_denim_corner',
      language: 'pt',
    })
    expect(input).not.toBeInTheDocument() // the view was remounted for the new business

    api.reply('AI', 'Vou chamar a equipe.')
    api.emit('conversation.state_changed', { type: 'conversation.state_changed', state: 'WAITING' })
    expect(await screen.findByText('Uma pessoa da equipe vai continuar esta conversa.')).toBeInTheDocument()

    api.reply('SYSTEM', 'A team member joined the conversation.', {
      event: 'TAKEN_OVER',
      authorName: 'Maria',
    })
    api.emit('conversation.state_changed', { type: 'conversation.state_changed', state: 'HUMAN' })
    api.reply('HUMAN', 'Oi! Posso ajudar com a troca.', { authorName: 'Maria' })

    expect(await screen.findByText('Maria entrou na conversa')).toBeInTheDocument()
    expect(await screen.findByText('Oi! Posso ajudar com a troca.')).toBeInTheDocument()
    expect(screen.getByText('Maria', { selector: '.message__author' })).toBeInTheDocument()
    // Taken over: the "a person will continue" notice is gone.
    expect(screen.queryByText('Uma pessoa da equipe vai continuar esta conversa.')).not.toBeInTheDocument()

    api.reply('SYSTEM', 'Back to the assistant.', { event: 'RELEASED' })
    api.emit('conversation.state_changed', { type: 'conversation.state_changed', state: 'AI' })
    expect(await screen.findByText('Você voltou para o assistente')).toBeInTheDocument()
  })

  it('reloads the history and continues the conversation after a page refresh', async () => {
    const api = installFakePublicApi()
    window.sessionStorage.setItem(
      'tendwise.demo.conversation.joes-pizza',
      JSON.stringify({ conversationId: CONVERSATION_ID, token: TOKEN, greeting: 'Welcome back!' }),
    )
    api.store('CUSTOMER', 'Are you open today?')
    const answer = api.store('AI', 'Yes, until 11 pm.')

    setup()

    expect(await screen.findByText('Yes, until 11 pm.')).toBeInTheDocument()
    expect(screen.getByText('Are you open today?')).toBeInTheDocument()
    expect(screen.getByText('Welcome back!')).toBeInTheDocument()
    await waitFor(() => expect(api.openStream()).toBeDefined())
    // The stream asks only for what is newer than the history it has.
    expect(api.openStream()!.headers.get('Last-Event-ID')).toBe(String(answer.id))
    expect(api.requestsTo('POST', BASE)).toHaveLength(0)
  })

  it('reconnects with Last-Event-ID and reloads the history after the stream drops', async () => {
    const api = installFakePublicApi()
    const user = userEvent.setup()
    render(<ConversationView business={BUSINESSES[0]} options={{ reconnectDelayMs: () => 0 }} />)
    await user.type(screen.getByLabelText('Message'), 'Hello{Enter}')
    await waitFor(() => expect(api.openStream()).toBeDefined())
    const first = api.reply('AI', 'Hi there!')
    await screen.findByText('Hi there!')

    // The connection drops; a reply is stored while it is down.
    api.openStream()!.close()
    api.store('AI', 'Anything else?')

    expect(await screen.findByText('Anything else?')).toBeInTheDocument()
    await waitFor(() => expect(api.streams).toHaveLength(2))
    expect(api.streams[1].headers.get('Last-Event-ID')).toBe(String(first.id + 1))
    // Each message is shown once, however often it was read.
    expect(screen.getAllByText('Hi there!')).toHaveLength(1)
  })

  it('forgets a conversation the server no longer knows', async () => {
    const api = installFakePublicApi()
    window.sessionStorage.setItem(
      'tendwise.demo.conversation.joes-pizza',
      JSON.stringify({ conversationId: CONVERSATION_ID, token: 'expired', greeting: 'Hi' }),
    )

    setup()

    await waitFor(() =>
      expect(window.sessionStorage.getItem('tendwise.demo.conversation.joes-pizza')).toBeNull(),
    )
    expect(api.streams).toHaveLength(0)
    expect(screen.getByRole('button', { name: 'Do you have gluten-free pizza?' })).toBeInTheDocument()
  })

  it('keeps one conversation per business when switching', async () => {
    const api = installFakePublicApi()
    const { user, input, picker } = setup()
    await user.type(input, 'Pizza question{Enter}')
    await screen.findByText('Pizza question')

    await user.selectOptions(picker, 'denim-corner')
    expect(screen.queryByText('Pizza question')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: DENIM.exampleQuestions[0] })).toBeInTheDocument()

    await user.selectOptions(picker, 'joes-pizza')
    // Joe's conversation comes back from sessionStorage and its history.
    expect(await screen.findByText('Pizza question')).toBeInTheDocument()
    expect(api.requestsTo('POST', BASE)).toHaveLength(1)
  })

  it('shows an error and gives the text back when sending fails', async () => {
    const api = installFakePublicApi()
    api.failNextSend(429, { title: 'Too many requests', detail: 'Too many messages in this conversation.' })
    const { user, input } = setup()

    await user.type(input, 'Are you open?{Enter}')

    expect(await screen.findByRole('alert')).toHaveTextContent('Too many messages in this conversation.')
    expect(input).toHaveValue('Are you open?')
    expect(screen.queryByText('Are you open?', { selector: 'p' })).not.toBeInTheDocument()
  })

  it('disables the send button for empty or too long input', async () => {
    const api = installFakePublicApi()
    const { user, input, sendButton } = setup()

    expect(sendButton).toBeDisabled()

    await user.type(input, '   ')
    expect(sendButton).toBeDisabled()

    await user.clear(input)
    await user.click(input)
    await user.paste('a'.repeat(1001))
    expect(sendButton).toBeDisabled()
    expect(screen.getByText('1001 / 1000')).toBeInTheDocument()

    // Enter does not send it either.
    await user.keyboard('{Enter}')
    expect(api.requests).toHaveLength(0)

    await user.keyboard('{Backspace}')
    expect(sendButton).toBeEnabled()
    expect(screen.getByText('1000 / 1000')).toBeInTheDocument()
  })
})
