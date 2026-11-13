"""A tiny reply-language guess for blocked messages.

A blocked message never reaches the classifier, so there is no classified
language yet. Counting a few common words of each language is enough to answer
a refusal in the customer's language; if nothing stands out we use the
business's default language.
"""

import re

from app.agent.intent import Language
from app.guardrails.normalize import normalize

# Common words that belong to one language only (normalised: lowercase, no accents).
# Words shared by two languages ("que", "de", "no", "me", "do", "as") are left out.
MARKER_WORDS: dict[Language, set[str]] = {
    "en": {
        "the", "you", "your", "is", "are", "what", "how", "please", "hi", "hello",
        "thanks", "my", "and", "ignore", "previous", "instructions", "rules", "can",
        "with", "for", "this", "that", "now", "order", "tell", "show",
    },
    "es": {
        "el", "la", "los", "las", "es", "eres", "por", "favor", "hola", "gracias",
        "tus", "del", "pero", "muy", "quiero", "puedes", "dime", "instrucciones",
        "reglas", "ahora", "usted", "sus", "ignora", "olvida", "muestra", "muestrame",
        "un", "una", "y", "con", "mi", "cuanto", "cuando", "donde", "tienen", "hay",
    },
    "pt": {
        "voce", "voces", "os", "nao", "sim", "obrigado", "obrigada", "ola", "oi",
        "seu", "sua", "suas", "seus", "uma", "um", "com", "meu", "minha", "agora",
        "esqueca", "mostre", "finja", "instrucoes", "regras", "isso", "esse", "essa",
        "quero", "pode", "tem", "onde", "quanto", "dos", "das", "pra", "vai",
        "muito", "tambem",
    },
}  # fmt: skip

WORD = re.compile(r"\w+")


def guess_language(message: str, default: Language) -> Language:
    """The language with the most marker words in the message; `default` on a tie or none."""
    words = WORD.findall(normalize(message))
    counts = {
        language: sum(word in markers for word in words)
        for language, markers in MARKER_WORDS.items()
    }
    best = max(counts, key=lambda language: counts[language])
    if counts[best] == 0 or counts[best] == counts[default]:
        return default
    return best
