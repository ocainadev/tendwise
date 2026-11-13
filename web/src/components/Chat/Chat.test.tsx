import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi, type Mock } from 'vitest'
import type { ChatResponse } from '../../api/client'
import { Chat } from './Chat'

const HANDOFF_NOTICE = 'A team member will continue this conversation.'

function reply(overrides: Partial<ChatResponse> = {}): ChatResponse {
  return {
    conversation_id: 'conv-1',
    reply: 'Yes, our gluten-free base costs $2 extra.',
    intent: 'catalog_question',
    language: 'en',
    sources: [],
    handoff: false,
    ...overrides,
  }
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

// Replaces fetch with a mock that answers each call with the next response.
function mockFetch(...responses: Response[]): Mock {
  const fetchMock = vi.fn()
  for (const response of responses) {
    fetchMock.mockResolvedValueOnce(response)
  }
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

// The JSON body sent in the given fetch call (0 = first call).
function sentBody(fetchMock: Mock, call: number) {
  const init = fetchMock.mock.calls[call][1] as RequestInit
  return JSON.parse(init.body as string)
}

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

describe('Chat', () => {
  it('sends the right payload and renders the reply', async () => {
    const fetchMock = mockFetch(jsonResponse(reply()))
    const { user, input } = setup()

    await user.type(input, 'Do you have gluten-free pizza?{Enter}')

    expect(await screen.findByText('Yes, our gluten-free base costs $2 extra.')).toBeInTheDocument()
    expect(screen.getByText('Do you have gluten-free pizza?')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/chat')
    expect(sentBody(fetchMock, 0)).toEqual({
      conversation_id: null,
      business_slug: 'joes-pizza',
      message: 'Do you have gluten-free pizza?',
    })
    expect(input).toHaveValue('')
    expect(input).toHaveFocus()
  })

  it('sends an example question when its chip is clicked', async () => {
    const fetchMock = mockFetch(jsonResponse(reply()))
    const { user } = setup()

    await user.click(screen.getByRole('button', { name: 'What time do you close on Friday?' }))

    await screen.findByText('Yes, our gluten-free base costs $2 extra.')
    expect(sentBody(fetchMock, 0).message).toBe('What time do you close on Friday?')
    // The chips are only for an empty conversation.
    expect(screen.queryByRole('button', { name: 'Do you have gluten-free pizza?' })).not.toBeInTheDocument()
  })

  it('reuses the returned conversation_id on the next message', async () => {
    const fetchMock = mockFetch(
      jsonResponse(reply({ conversation_id: 'conv-42', reply: 'First answer' })),
      jsonResponse(reply({ conversation_id: 'conv-42', reply: 'Second answer' })),
    )
    const { user, input } = setup()

    await user.type(input, 'Hello{Enter}')
    await screen.findByText('First answer')
    await user.type(input, 'And on Sunday?{Enter}')
    await screen.findByText('Second answer')

    expect(sentBody(fetchMock, 0).conversation_id).toBeNull()
    expect(sentBody(fetchMock, 1).conversation_id).toBe('conv-42')
  })

  it('starts a new conversation when the business changes', async () => {
    const fetchMock = mockFetch(
      jsonResponse(reply({ conversation_id: 'conv-pizza', reply: 'Pizza answer' })),
      jsonResponse(reply({ conversation_id: 'conv-denim', reply: 'Fazemos sim!', language: 'pt' })),
    )
    const { user, input, picker } = setup()

    await user.type(input, 'Hello{Enter}')
    await screen.findByText('Pizza answer')

    await user.selectOptions(picker, 'denim-corner')

    expect(screen.queryByText('Pizza answer')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Vocês fazem ajuste de barra?' })).toBeInTheDocument()

    await user.type(input, 'Vocês fazem ajuste de barra?{Enter}')
    await screen.findByText('Fazemos sim!')

    expect(sentBody(fetchMock, 1)).toEqual({
      conversation_id: null,
      business_slug: 'denim-corner',
      message: 'Vocês fazem ajuste de barra?',
    })
  })

  it('shows the sources under a clerk reply', async () => {
    mockFetch(
      jsonResponse(
        reply({
          sources: [
            { title: 'Menu', source_path: 'joes-pizza/menu.md', section: 'Pizzas' },
            { title: 'FAQ', source_path: 'joes-pizza/faq.md', section: null },
          ],
        }),
      ),
    )
    const { user, input } = setup()

    await user.type(input, 'Do you have gluten-free pizza?{Enter}')

    expect(await screen.findByText('Sources (2)')).toBeInTheDocument()
    expect(screen.getByText('Menu – Pizzas')).toBeInTheDocument()
    expect(screen.getByText('FAQ')).toBeInTheDocument()
  })

  it('shows the handoff notice only when handoff is true', async () => {
    mockFetch(
      jsonResponse(reply({ reply: 'Here is our menu.', handoff: false })),
      jsonResponse(reply({ reply: 'I am passing you to the team.', intent: 'human_request', handoff: true })),
    )
    const { user, input } = setup()

    await user.type(input, 'Menu please{Enter}')
    await screen.findByText('Here is our menu.')
    expect(screen.queryByText(HANDOFF_NOTICE)).not.toBeInTheDocument()

    await user.type(input, 'I want to talk to a person{Enter}')
    await screen.findByText('I am passing you to the team.')
    expect(screen.getByText(HANDOFF_NOTICE)).toBeInTheDocument()
  })

  it('shows an error banner and keeps the typed message when the request fails', async () => {
    mockFetch(jsonResponse({ detail: 'The clerk is unavailable right now.' }, 503))
    const { user, input } = setup()

    await user.type(input, 'Are you open?{Enter}')

    expect(await screen.findByRole('alert')).toHaveTextContent('The clerk is unavailable right now.')
    expect(input).toHaveValue('Are you open?')
    // The unanswered message is not left in the conversation.
    expect(screen.queryByText('Are you open?', { selector: 'p' })).not.toBeInTheDocument()
  })

  it('disables sending and shows the typing indicator while waiting for the reply', async () => {
    let answer: (response: Response) => void = () => {}
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise<Response>((resolve) => (answer = resolve))),
    )
    const { user, input, sendButton, picker } = setup()

    await user.type(input, 'Hello')
    await user.click(sendButton)

    expect(screen.getByText("Joe's Pizza is typing…")).toBeInTheDocument()
    await user.type(input, 'Another one')
    expect(sendButton).toBeDisabled()
    expect(picker).toBeDisabled()

    answer(jsonResponse(reply({ reply: 'Hi there!' })))

    await screen.findByText('Hi there!')
    expect(screen.queryByText("Joe's Pizza is typing…")).not.toBeInTheDocument()
    expect(sendButton).toBeEnabled()
  })

  it('disables the send button for empty or too long input', async () => {
    const fetchMock = mockFetch()
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
    expect(fetchMock).not.toHaveBeenCalled()

    await user.keyboard('{Backspace}')
    expect(sendButton).toBeEnabled()
    expect(screen.getByText('1000 / 1000')).toBeInTheDocument()
  })
})
