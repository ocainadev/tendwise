import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Markdown } from './Markdown'

describe('Markdown', () => {
  it('renders headings, paragraphs, lists and inline styles', () => {
    const { container } = render(
      <Markdown
        text={[
          '# Horário',
          '',
          'Abrimos **todos os dias**, exceto *segunda*.',
          'Sem reservas.',
          '',
          '- Terça a quinta: `11h–22h`',
          '- Sexta: 11h–23h30',
          '',
          '1. Primeiro',
          '2. Segundo',
          '',
          '```',
          'código **sem** formatação',
          '```',
        ].join('\n')}
      />,
    )

    expect(screen.getByRole('heading', { level: 1, name: 'Horário' })).toBeInTheDocument()
    expect(screen.getByText('todos os dias').tagName).toBe('STRONG')
    expect(screen.getByText('segunda').tagName).toBe('EM')
    // Lines of one paragraph are joined with a space.
    expect(container.querySelector('p')).toHaveTextContent('Abrimos todos os dias, exceto segunda. Sem reservas.')
    expect(container.querySelectorAll('ul > li')).toHaveLength(2)
    expect(container.querySelectorAll('ol > li')).toHaveLength(2)
    expect(screen.getByText('11h–22h').tagName).toBe('CODE')
    expect(container.querySelector('pre')).toHaveTextContent('código **sem** formatação')
  })

  it('shows HTML as text instead of running it', () => {
    const { container } = render(<Markdown text={'<script>alert(1)</script> <img src=x onerror=alert(1)>'} />)

    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
    expect(container).toHaveTextContent('<script>alert(1)</script>')
  })

  it('links only to http(s) and mailto addresses', () => {
    render(<Markdown text={'[site](https://example.test) e [perigo](javascript:alert(1))'} />)

    const link = screen.getByRole('link', { name: 'site' })
    expect(link).toHaveAttribute('href', 'https://example.test')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.queryByRole('link', { name: 'perigo' })).not.toBeInTheDocument()
    expect(screen.getByText(/perigo/)).toBeInTheDocument()
  })
})
