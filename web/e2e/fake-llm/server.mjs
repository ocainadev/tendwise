// A fake LLM for the end-to-end tests: a tiny server that speaks just enough
// of OpenAI's Responses API (POST /v1/responses) for agent-service's OpenAI
// adapter. agent-service is pointed at it with OPENAI_BASE_URL (see
// ../docker-compose.e2e.yml), so the whole stack runs without a real key and
// every answer is predictable. Plain Node, no dependencies.
//
// - The intent classifier gets JSON: "human_request" when the message asks
//   for a person, otherwise "catalog_question" (so retrieval and sources run).
// - The handoff note gets a fixed summary.
// - Every other call (the answer) gets FAKE_ANSWER.

import { createServer } from 'node:http'

const PORT = Number(process.env.PORT ?? 4010)
export const FAKE_ANSWER = 'Resposta de teste do LLM falso.'
const ASKS_FOR_PERSON = /\b(pessoa|humano|atendente|person|human)\b/i

function latestMessage(text) {
  const match = /<latest_customer_message>\s*([\s\S]*?)\s*<\/latest_customer_message>/.exec(text)
  return match ? match[1] : text
}

function answerFor(instructions, input) {
  const text = input.map((item) => (typeof item.content === 'string' ? item.content : '')).join('\n')
  if (instructions.includes('intent classifier')) {
    const message = latestMessage(text)
    const language = /Business default language: (en|es|pt)/.exec(text)?.[1] ?? 'en'
    return JSON.stringify({
      intent: ASKS_FOR_PERSON.test(message) ? 'human_request' : 'catalog_question',
      confidence: 0.9,
      sentiment: 'neutral',
      language,
      search_query: message,
    })
  }
  if (instructions.includes('handoff notes')) {
    return 'The customer asked to talk to a person (end-to-end test).'
  }
  return FAKE_ANSWER
}

function responseBody(text) {
  return {
    id: `resp_${Date.now()}`,
    object: 'response',
    created_at: Math.floor(Date.now() / 1000),
    status: 'completed',
    model: 'fake-llm',
    output: [
      {
        type: 'message',
        id: `msg_${Date.now()}`,
        status: 'completed',
        role: 'assistant',
        content: [{ type: 'output_text', text, annotations: [] }],
      },
    ],
    parallel_tool_calls: false,
    tool_choice: 'auto',
    tools: [],
    usage: {
      input_tokens: 10,
      input_tokens_details: { cached_tokens: 0 },
      output_tokens: 10,
      output_tokens_details: { reasoning_tokens: 0 },
      total_tokens: 20,
    },
  }
}

createServer((request, response) => {
  if (request.method !== 'POST' || !request.url.endsWith('/responses')) {
    response.writeHead(404, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ error: { message: 'Not found' } }))
    return
  }
  let raw = ''
  request.on('data', (chunk) => (raw += chunk))
  request.on('end', () => {
    const body = JSON.parse(raw || '{}')
    const text = answerFor(String(body.instructions ?? ''), Array.isArray(body.input) ? body.input : [])
    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify(responseBody(text)))
  })
}).listen(PORT, () => console.log(`fake LLM listening on ${PORT}`))
