"""The output check: no phone numbers or e-mail addresses in a reply, and a length cap.

Why mask in the reply at all: the reply is written by an LLM, which may repeat
a number it saw in a document or in the customer's own message. business-service
never sends customer names (or phones) to the agent, so the tool data in the
state has none to leak; masking covers what the model could still write.

Phone masking reuses `mask_phone_last4` from `app/agent/privacy.py`, so the
reply and the stored tool calls use the same `****1234` format.
"""

import re

from app.agent.privacy import mask_phone_last4

MAX_REPLY_CHARS = 1200
ELLIPSIS = " …"

# A phone-like run: an optional "+", then digits mixed with spaces, dots, dashes
# and parentheses, ending in a digit. Too broad on its own, so:
# - it must not start right after a letter, digit, "-", ".", ",", "/" or ":", which
#   keeps order codes (JP-1042), decimals and times (11:00) out;
# - it must not follow "$" or "$ " (prices such as "R$ 1.299.000");
# - it must not be an ISO date (2026-11-12);
# - `mask_phones` then only masks runs with 7 or more digits.
PHONE_CANDIDATE = re.compile(
    r"(?<![\w.,/:-])(?<!\$)(?<!\$ )(?!\d{4}-\d{2}-\d{2})\+?\(?\d[\d ().-]{5,}\d"
)
MIN_PHONE_DIGITS = 7

# local-part@domain.tld; the domain is kept, the personal part is hidden.
EMAIL = re.compile(r"\b[\w.+-]+@([\w-]+(?:\.[\w-]+)+)\b")

# The end of a sentence: ".", "!", "?" or "…" followed by a space or a new line.
SENTENCE_END = re.compile(r"[.!?…](?=\s)")


def mask_phones(text: str) -> str:
    """Replace each phone number with `****` + its last 4 digits."""

    def mask(match: re.Match[str]) -> str:
        digits = re.sub(r"\D", "", match.group())
        if len(digits) < MIN_PHONE_DIGITS:
            return match.group()  # a short number, not a phone
        return mask_phone_last4(digits[-4:])

    return PHONE_CANDIDATE.sub(mask, text)


def mask_emails(text: str) -> str:
    """`ana.souza@gmail.com` -> `****@gmail.com`."""
    return EMAIL.sub(r"****@\1", text)


def cap_length(text: str) -> str:
    """Cut a reply over MAX_REPLY_CHARS at the last full sentence and add "…".

    If no sentence ends in the second half of the allowed length, cut at the
    last space instead, so we never keep only a tiny first sentence.
    """
    if len(text) <= MAX_REPLY_CHARS:
        return text
    limit = MAX_REPLY_CHARS - len(ELLIPSIS)
    sentence_ends = [m.end() for m in SENTENCE_END.finditer(text) if m.end() <= limit]
    if sentence_ends and sentence_ends[-1] >= limit // 2:
        kept = text[: sentence_ends[-1]]
    else:
        kept = text[:limit].rsplit(" ", 1)[0]
    return kept.rstrip() + ELLIPSIS


def make_reply_safe(reply: str) -> str:
    """Mask personal data first, then cap the length (masking only makes text shorter)."""
    return cap_length(mask_phones(mask_emails(reply)))
