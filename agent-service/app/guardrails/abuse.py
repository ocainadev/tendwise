"""A small baseline list of clearly abusive phrases, in English, Spanish and Portuguese.

Why so small: a customer who swears about a cold pizza is complaining, and a
complaint must reach a person (the router's handoff), not be blocked. So the
list holds insults aimed at someone ("fuck you", "you idiot"), not every swear
word. It is a baseline: a moderation API or an LLM check could replace it later.

Phrases are written like the normalised message: lowercase, without accents.
"""

import re

from app.guardrails.normalize import normalize

ABUSIVE_PHRASES: list[str] = [
    # English
    "fuck you",
    "fuck off",
    "piece of shit",
    "asshole",
    "dumbass",
    "you idiot",
    "you moron",
    "you are an idiot",
    "you're an idiot",
    "you are stupid",
    "you're stupid",
    "stupid bot",
    # Spanish
    "hijo de puta",
    "hija de puta",
    "vete a la mierda",
    "pendejo",
    "pendeja",
    "gilipollas",
    "eres un idiota",
    "eres idiota",
    "eres un estupido",
    # Portuguese
    "vai se foder",
    "vai tomar no cu",
    "filho da puta",
    "filha da puta",
    "arrombado",
    "babaca",
    "seu idiota",
    "voce e idiota",
    "voce e um idiota",
    "voce e burro",
    # Spanish and Portuguese
    "imbecil",
]

# One regex for the whole list: each phrase as whole words, any spacing between them.
ABUSE_PATTERN = re.compile(
    r"\b(?:"
    + "|".join(r"\s+".join(map(re.escape, phrase.split())) for phrase in ABUSIVE_PHRASES)
    + r")\b"
)


def is_abusive(message: str) -> bool:
    """True if the message contains one of the abusive phrases."""
    return ABUSE_PATTERN.search(normalize(message)) is not None
