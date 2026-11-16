import { screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import type { BusinessSettings } from '../api/settings'
import { JOES_AGENT, JOES_OWNER, fakeBackend, makeUser, renderApp } from '../test/renderApp'

const SETTINGS_URL = '/api/v1/panel/joes-pizza/settings'

function makeSettings(): BusinessSettings {
  return {
    name: "Joe's Pizza",
    category: 'pizzaria',
    defaultLanguage: 'pt',
    timezone: 'America/Sao_Paulo',
    openingHours: {
      mon: [],
      tue: [{ open: '11:00', close: '22:00' }],
      wed: [{ open: '11:00', close: '22:00' }],
      thu: [{ open: '11:00', close: '22:00' }],
      fri: [{ open: '11:00', close: '23:00' }],
      sat: [{ open: '11:00', close: '15:00' }, { open: '18:00', close: '23:00' }],
      sun: [{ open: '18:00', close: '23:00' }],
    },
    assistantSettings: {
      name: 'Bia',
      tone: null,
      greeting: null,
      extraInstructions: null,
      languages: ['pt', 'en'],
      outOfHoursMessage: null,
    },
    handoffSettings: { autoAckMinutes: 5 },
    widgetSettings: { color: '#0f766e', position: 'bottom-right', allowedDomains: ['https://joes.example.com'] },
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

// fakeBackend's auth API plus GET/PUT /settings. `putAnswer` replaces the
// normal PUT answer (which echoes the body back).
function fakeSettingsApi(memberships = [JOES_OWNER], putAnswer?: () => Response): Mock {
  const authFetch = fakeBackend({ loggedIn: makeUser(memberships) })
  let stored = makeSettings()
  const fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
    const method = init.method ?? 'GET'
    if (url !== SETTINGS_URL) {
      return authFetch(url, init)
    }
    if (method === 'GET') {
      return json(stored)
    }
    if (method === 'PUT') {
      if (putAnswer) {
        return putAnswer()
      }
      stored = JSON.parse(init.body as string)
      return json(stored)
    }
    throw new Error(`Unexpected request in test: ${method} ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function putBodies(fetchMock: Mock): BusinessSettings[] {
  return fetchMock.mock.calls
    .filter(([url, init]) => url === SETTINGS_URL && (init as RequestInit | undefined)?.method === 'PUT')
    .map(([, init]) => JSON.parse((init as RequestInit).body as string))
}

beforeEach(() => {
  document.cookie = 'tendwise_csrf=csrf-value'
})

describe('settings page', () => {
  it('loads the settings into every section', async () => {
    fakeSettingsApi()
    renderApp('/app/joes-pizza/settings')

    expect(await screen.findByLabelText('Nome da empresa')).toHaveValue("Joe's Pizza")
    expect(screen.getByLabelText('Fuso horário')).toHaveValue('America/Sao_Paulo')
    expect(screen.getByLabelText('Nome do assistente')).toHaveValue('Bia')
    expect(screen.getByLabelText('Aviso automático após (minutos)')).toHaveValue(5)
    expect(screen.getByRole('checkbox', { name: 'Português' })).toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Espanhol' })).not.toBeChecked()
    expect(screen.getByText('https://joes.example.com')).toBeInTheDocument()
    expect(screen.getByLabelText('Sábado: abre (intervalo 2)')).toHaveValue('18:00')
    const monday = screen.getByRole('group', { name: 'Segunda' })
    expect(within(monday).getByRole('checkbox', { name: 'Fechado' })).toBeChecked()
  })

  it('saves the whole form with one PUT and shows a toast', async () => {
    const fetchMock = fakeSettingsApi()
    const { user } = renderApp('/app/joes-pizza/settings')

    const greeting = await screen.findByLabelText('Saudação')
    await user.type(greeting, 'Olá!')
    await user.click(screen.getByRole('checkbox', { name: 'Espanhol' }))
    await user.type(screen.getByLabelText('Novo site'), 'https://www.joes.com.br{Enter}')
    await user.click(screen.getByRole('button', { name: 'Salvar alterações' }))

    expect(await screen.findByText('Configurações salvas.')).toHaveAttribute('role', 'status')
    const [body] = putBodies(fetchMock)
    expect(body.assistantSettings.greeting).toBe('Olá!')
    expect(body.assistantSettings.languages).toEqual(['pt', 'en', 'es'])
    // Blank optional texts are sent as null ("not set").
    expect(body.assistantSettings.tone).toBeNull()
    expect(body.widgetSettings.allowedDomains).toEqual(['https://joes.example.com', 'https://www.joes.com.br'])
    expect(body.openingHours.sat).toHaveLength(2)
    const putInit = fetchMock.mock.calls.find(([url, init]) => url === SETTINGS_URL && init?.method === 'PUT')?.[1]
    expect(new Headers(putInit.headers).get('X-CSRF-Token')).toBe('csrf-value')
  })

  it('counts the characters of the extra instructions against the cap', async () => {
    fakeSettingsApi()
    const { user } = renderApp('/app/joes-pizza/settings')

    const field = await screen.findByLabelText('Instruções extras')
    expect(field).toHaveAttribute('maxLength', '1000')
    expect(screen.getByText('0 de 1000 caracteres')).toBeInTheDocument()
    await user.type(field, 'Nunca prometa entrega grátis.')
    expect(screen.getByText('29 de 1000 caracteres')).toBeInTheDocument()
  })

  it('shows the API field errors next to their fields', async () => {
    fakeSettingsApi([JOES_OWNER], () =>
      json(
        {
          status: 400,
          title: 'Invalid request',
          errors: [
            { field: 'timezone', message: 'must be an IANA time zone, e.g. America/Sao_Paulo' },
            { field: 'widgetSettings.allowedDomains[0]', message: 'must be an origin such as https://shop.example.com' },
          ],
        },
        400,
      ),
    )
    const { user } = renderApp('/app/joes-pizza/settings')

    await user.click(await screen.findByRole('button', { name: 'Salvar alterações' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Alguns campos precisam de correção')
    expect(screen.getByText('Fuso horário inválido. Use um como America/Sao_Paulo.')).toBeInTheDocument()
    const domain = screen.getByText('https://joes.example.com').closest('li') as HTMLElement
    expect(within(domain).getByText(/Use só o endereço do site/)).toBeInTheDocument()
    expect(screen.queryByText('Configurações salvas.')).not.toBeInTheDocument()
  })

  it('keeps AGENT out: no settings menu item, the URL goes to the inbox', async () => {
    const fetchMock = fakeSettingsApi([JOES_AGENT])
    renderApp('/app/joes-pizza/settings')

    await waitFor(() => expect(screen.getByTestId('current-path')).toHaveTextContent('/app/joes-pizza/inbox'))
    expect(screen.queryByRole('link', { name: 'Configurações' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Nome da empresa')).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([url]) => url === SETTINGS_URL)).toBe(false)
  })
})

describe('opening hours editor', () => {
  it('adds and removes intervals, and closes a day', async () => {
    const fetchMock = fakeSettingsApi()
    const { user } = renderApp('/app/joes-pizza/settings')

    const tuesday = await screen.findByRole('group', { name: 'Terça' })
    await user.click(within(tuesday).getByRole('button', { name: 'Adicionar intervalo em Terça' }))
    expect(screen.getByLabelText('Terça: abre (intervalo 2)')).toHaveValue('09:00')

    // At most 4 intervals per day.
    const add = within(tuesday).getByRole('button', { name: 'Adicionar intervalo em Terça' })
    await user.click(add)
    await user.click(add)
    expect(add).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Terça: remover intervalo 4' }))
    await user.click(screen.getByRole('button', { name: 'Terça: remover intervalo 3' }))
    expect(screen.queryByLabelText('Terça: abre (intervalo 3)')).not.toBeInTheDocument()

    // Opening Monday gives it one interval; closing Sunday empties it.
    const monday = screen.getByRole('group', { name: 'Segunda' })
    await user.click(within(monday).getByRole('checkbox', { name: 'Fechado' }))
    expect(screen.getByLabelText('Segunda: abre (intervalo 1)')).toBeInTheDocument()
    const sunday = screen.getByRole('group', { name: 'Domingo' })
    await user.click(within(sunday).getByRole('checkbox', { name: 'Fechado' }))

    await user.click(screen.getByRole('button', { name: 'Salvar alterações' }))
    await screen.findByText('Configurações salvas.')
    const [body] = putBodies(fetchMock)
    expect(body.openingHours.tue).toEqual([
      { open: '11:00', close: '22:00' },
      { open: '09:00', close: '18:00' },
    ])
    expect(body.openingHours.mon).toEqual([{ open: '09:00', close: '18:00' }])
    expect(body.openingHours.sun).toEqual([])
  })
})
