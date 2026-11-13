"""Prompt-injection patterns: obvious attempts to change the assistant's instructions.

Each rule is a family of regexes in English, Spanish and Portuguese. They run
on the normalised message (lowercase, no accents, see `normalize.py`), so the
patterns are written in lowercase without accents ("instrucoes", not
"instruções").

The patterns are deliberately narrow: they need the words that make a sentence
an attack ("ignore *previous instructions*", "act *as a*"), so ordinary
questions like "ignore the mushrooms on my pizza" or "what's your system for
deliveries?" pass. Anything subtler is left to the system prompts, which tell
the model to stay in scope.
"""

import re

from app.guardrails.normalize import normalize

# A filler word or two between the verb and the noun ("ignore *all of the* previous...").
# Bounded on purpose: `{0,2}` keeps every regex fast even on a 1,000-character message.
WORDS = r"(?:\w+\s+){0,2}"

INJECTION_RULES: dict[str, list[str]] = {
    # "Ignore all previous instructions", "olvida las instrucciones anteriores".
    "ignore_previous": [
        rf"\b(?:ignore|disregard|forget|override)\s+{WORDS}(?:previous|prior|above|earlier)"
        r"\s+(?:\w+\s+)?(?:instructions?|rules|prompts?|messages|directions)\b",
        rf"\b(?:ignora|ignore|olvida|olvide|descarta|omite)\s+{WORDS}"
        r"(?:instrucciones|reglas|indicaciones|ordenes)\s+(?:anteriores|previas)\b",
        rf"\b(?:ignore|ignora|esqueca|esquece|desconsidere|descarte)\s+{WORDS}"
        r"(?:instrucoes|regras|orientacoes|ordens)\s+(?:anteriores|previas|acima)\b",
    ],
    # "Disregard your rules", "olvida tus reglas", "esqueça suas regras".
    "disregard_rules": [
        r"\b(?:ignore|disregard|forget|bypass|override)\s+(?:all\s+)?(?:of\s+)?"
        r"(?:your|the|these|any)\s+(?:rules|instructions|guidelines|restrictions|programming)\b",
        r"\b(?:ignora|olvida|olvidate\s+de|omite|salta)\s+(?:todas\s+)?"
        r"(?:tus|las|sus)\s+(?:reglas|instrucciones|indicaciones|restricciones)\b",
        r"\b(?:ignore|ignora|esqueca|esquece|desconsidere|burle)\s+(?:todas\s+)?(?:as\s+)?"
        r"(?:suas|tuas|as|seus)\s+(?:regras|instrucoes|orientacoes|restricoes)\b",
    ],
    # "You are now...", "act as a...", "pretend to be...", "finja que você é...".
    "role_change": [
        r"\byou\s+are\s+now\b",
        r"\bfrom\s+now\s+on,?\s+you\s+(?:are|will\s+be)\b",
        r"\bpretend\s+(?:to\s+be|you\s+are|you're|that\s+you)\b",
        r"\bact\s+as\s+(?:a|an|if|my|the)\b",
        r"\broleplay\s+as\b",
        r"\bahora\s+eres\b",
        r"\b(?:finge|finja|imagina)\s+(?:que\s+eres|ser)\b",
        r"\b(?:actua|actue)\s+como\b",
        r"\bagora\s+voce\s+e\b",
        r"\b(?:finja|finge)\s+(?:que\s+voce\s+e|que\s+e|ser)\b",
        r"\b(?:aja|atue|comporte-se)\s+como\b",
    ],
    # Asking about the hidden prompt by name.
    "system_prompt": [
        r"\bsystem\s+(?:prompt|message)\b",
        r"\b(?:prompt|mensaje)\s+del?\s+sistema\b",
        r"\b(?:prompt|mensagem)\s+d[oe]\s+sistema\b",
    ],
    # "Reveal your instructions", "muéstrame tu prompt", "mostre seu prompt".
    "reveal_prompt": [
        r"\b(?:reveal|show|print|repeat|tell|give|display|output|share|leak)\s+(?:me\s+)?"
        r"(?:your|the\s+(?:original|initial|hidden|secret))\s+(?:\w+\s+)?(?:instructions|prompt)\b",
        r"\bwhat\s+(?:are|were)\s+your\s+(?:original\s+|initial\s+)?instructions\b",
        r"\b(?:muestra|muestrame|revela|revelame|dime|ensename|repite|imprime)\s+"
        r"(?:tus|tu|las|el)\s+(?:\w+\s+)?(?:instrucciones|prompt)\b",
        r"\b(?:mostre|mostra|me\s+mostre|revele|revela|diga|me\s+diga|repita|imprima)\s+"
        r"(?:suas|seu|as|o|tuas|teu)\s+(?:\w+\s+)?(?:instrucoes|prompt)\b",
    ],
    # "Developer mode", "modo desarrollador", "modo desenvolvedor".
    "developer_mode": [
        r"\b(?:developer|dev|debug|god|admin)\s+mode\b",
        r"\bmodo\s+(?:desarrollador|desenvolvedor|dios|deus|admin|administrador|depuracion|depuracao)\b",
    ],
    # The word itself, in any language. "DAN" is checked separately (see below).
    "jailbreak": [
        r"\bjailbr[ea]ak\w*\b",
        r"\bdo\s+anything\s+now\b",
    ],
    # Chat-format tags pasted to fake a system or assistant turn.
    "role_tag": [
        r"<\s*/?\s*(?:system|assistant|instructions?)\s*>",
        r"\[\s*/?\s*(?:inst|sys)\s*\]",
        r"<<\s*/?\s*sys\s*>>",
        r"<\|\s*(?:system|im_start|im_end|endoftext)\s*\|>",
        r"^\s*#{2,}\s*(?:system|instructions?)\b",
        r"^\s*system\s*:",
    ],
}

COMPILED_RULES: dict[str, list[re.Pattern[str]]] = {
    name: [re.compile(pattern, re.MULTILINE) for pattern in patterns]
    for name, patterns in INJECTION_RULES.items()
}

# The "DAN" jailbreak prompt, in capitals only: a lowercase "Dan" is a customer's name.
DAN_PATTERN = re.compile(r"\bDAN\b")


def find_injection(message: str) -> str | None:
    """The name of the first injection rule the message matches, or None.

    Only the rule name leaves this function, so it can be logged safely: the
    message text itself may hold personal data.
    """
    text = normalize(message)
    for name, patterns in COMPILED_RULES.items():
        if any(pattern.search(text) for pattern in patterns):
            return name
    if DAN_PATTERN.search(message):
        return "jailbreak"
    return None
