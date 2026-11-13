import type { Business } from '../../businesses'

interface ExampleQuestionsProps {
  business: Business
  onPick: (question: string) => void
}

// Shown while the conversation is empty, so a visitor knows what to ask.
export function ExampleQuestions({ business, onPick }: ExampleQuestionsProps) {
  return (
    <section className="examples" aria-labelledby="examples-title">
      <h2 id="examples-title" className="examples__title">
        Ask {business.name} anything
      </h2>
      <p className="examples__hint">
        Answers come from the business's own catalog, FAQ and policies. Try one of these:
      </p>
      <ul className="examples__list">
        {business.exampleQuestions.map((question) => (
          <li key={question}>
            <button
              type="button"
              className="examples__chip"
              lang={business.language}
              onClick={() => onPick(question)}
            >
              {question}
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}
