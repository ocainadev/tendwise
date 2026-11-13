"""The input check: decides whether a customer message may reach the agent."""

from dataclasses import dataclass

from app.guardrails.abuse import is_abusive
from app.guardrails.injection import find_injection
from app.guardrails.replies import BlockKind

# Spec section 6: messages are capped at 1,000 characters.
MAX_MESSAGE_CHARS = 1000


@dataclass(frozen=True)
class Block:
    """Why a message was blocked: `kind` picks the reply, `reason` goes to the turn log."""

    kind: BlockKind
    reason: str  # e.g. "length:too_long", "injection:ignore_previous", "abuse"


def check_message(message: str) -> Block | None:
    """Return why the message must be blocked, or None if it may go on.

    Length is checked first: it is the cheapest check, and it also bounds the
    work the regexes below have to do.
    """
    text = message.strip()
    if not text:
        return Block(kind="empty", reason="length:empty")
    if len(text) > MAX_MESSAGE_CHARS:
        return Block(kind="too_long", reason="length:too_long")
    rule = find_injection(text)
    if rule:
        return Block(kind="injection", reason=f"injection:{rule}")
    if is_abusive(text):
        return Block(kind="abuse", reason="abuse")
    return None
