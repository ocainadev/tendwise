import type { ReactNode } from 'react'

// A small markdown renderer for the document preview. It knows the subset the
// knowledge base documents use: headings, paragraphs, bullet and numbered
// lists, fenced code blocks, **bold**, *italic*, `code` and [links](https://…).
//
// Why not a library: this is only a preview, and these few rules cover the
// documents. It is also safe by construction: it builds React elements, never
// HTML strings, so any HTML in a document is shown as plain text (React
// escapes it), and only http(s) and mailto links become <a> elements.

type Block =
  | { kind: 'heading'; level: number; text: string }
  | { kind: 'paragraph'; text: string }
  | { kind: 'list'; ordered: boolean; items: string[] }
  | { kind: 'code'; text: string }

const HEADING = /^(#{1,6})\s+(.*)$/
const BULLET_ITEM = /^\s*[-*+]\s+(.*)$/
const NUMBERED_ITEM = /^\s*\d+[.)]\s+(.*)$/
const FENCE = /^\s*```/

// Splits the text into blocks, line by line.
export function parseBlocks(markdown: string): Block[] {
  const blocks: Block[] = []
  const lines = markdown.replace(/\r\n?/g, '\n').split('\n')
  let i = 0
  while (i < lines.length) {
    const line = lines[i]

    if (line.trim() === '') {
      i += 1
      continue
    }

    if (FENCE.test(line)) {
      const code: string[] = []
      i += 1
      while (i < lines.length && !FENCE.test(lines[i])) {
        code.push(lines[i])
        i += 1
      }
      i += 1 // the closing fence (or the end of the text)
      blocks.push({ kind: 'code', text: code.join('\n') })
      continue
    }

    const heading = HEADING.exec(line)
    if (heading) {
      blocks.push({ kind: 'heading', level: heading[1].length, text: heading[2].trim() })
      i += 1
      continue
    }

    const itemPattern = BULLET_ITEM.test(line) ? BULLET_ITEM : NUMBERED_ITEM.test(line) ? NUMBERED_ITEM : null
    if (itemPattern) {
      const items: string[] = []
      let match = itemPattern.exec(lines[i] ?? '')
      while (i < lines.length && match) {
        items.push(match[1])
        i += 1
        match = itemPattern.exec(lines[i] ?? '')
      }
      blocks.push({ kind: 'list', ordered: itemPattern === NUMBERED_ITEM, items })
      continue
    }

    // A paragraph runs until a blank line or the start of another block.
    const text: string[] = []
    while (
      i < lines.length &&
      lines[i].trim() !== '' &&
      !FENCE.test(lines[i]) &&
      !HEADING.test(lines[i]) &&
      !BULLET_ITEM.test(lines[i]) &&
      !NUMBERED_ITEM.test(lines[i])
    ) {
      text.push(lines[i].trim())
      i += 1
    }
    blocks.push({ kind: 'paragraph', text: text.join(' ') })
  }
  return blocks
}

const INLINE = /(`[^`]+`|\*\*[^*]+\*\*|__[^_]+__|\*[^*]+\*|_[^_]+_|\[[^\]]+\]\([^)\s]+\))/
const LINK = /^\[([^\]]+)\]\(([^)\s]+)\)$/
const SAFE_URL = /^(https?:\/\/|mailto:)/i

function renderInline(text: string): ReactNode[] {
  // With a capturing group, split keeps the matches at the odd indexes.
  return text.split(INLINE).map((part, index) => {
    if (index % 2 === 0) {
      return part
    }
    if (part.startsWith('`')) {
      return <code key={index}>{part.slice(1, -1)}</code>
    }
    if (part.startsWith('**') || part.startsWith('__')) {
      return <strong key={index}>{part.slice(2, -2)}</strong>
    }
    const link = LINK.exec(part)
    if (link) {
      const [, label, url] = link
      return SAFE_URL.test(url) ? (
        <a key={index} href={url} target="_blank" rel="noopener noreferrer">
          {label}
        </a>
      ) : (
        label
      )
    }
    return <em key={index}>{part.slice(1, -1)}</em>
  })
}

function renderBlock(block: Block, index: number): ReactNode {
  switch (block.kind) {
    case 'heading': {
      const Tag = `h${block.level}` as 'h1'
      return <Tag key={index}>{renderInline(block.text)}</Tag>
    }
    case 'paragraph':
      return <p key={index}>{renderInline(block.text)}</p>
    case 'code':
      return (
        <pre key={index}>
          <code>{block.text}</code>
        </pre>
      )
    case 'list': {
      const items = block.items.map((item, itemIndex) => <li key={itemIndex}>{renderInline(item)}</li>)
      return block.ordered ? <ol key={index}>{items}</ol> : <ul key={index}>{items}</ul>
    }
  }
}

export function Markdown({ text }: { text: string }) {
  return <div className="knowledge-markdown">{parseBlocks(text).map(renderBlock)}</div>
}
