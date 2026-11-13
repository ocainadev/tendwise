"""Masking helpers, so personal data never reaches logs or stored tool calls."""

import re

# A run of digits that may hold spaces (not newlines), dots, dashes or parentheses, with an
# optional leading "+": "+1 (555) 123-4567", "11 98765-4321", "555.123.4567".
PHONE_LIKE_PATTERN = re.compile(r"\+?\d[\d ().-]*\d")
# Phone numbers have 9+ digits (with area code); fewer is an order code, price,
# time or ISO date ("2026-11-12" has 8), which we keep.
MIN_PHONE_DIGITS = 9


def mask_phone_last4(digits: str) -> str:
    """`5678` -> `****5678`: the format the spec uses for phone numbers in logs."""
    return f"****{digits}"


def mask_phone_numbers(text: str) -> str:
    """Replace every phone-like number in `text` with `****` + its last 4 digits."""

    def mask(match: re.Match[str]) -> str:
        digits = re.sub(r"\D", "", match.group())
        if len(digits) < MIN_PHONE_DIGITS:
            return match.group()
        return mask_phone_last4(digits[-4:])

    return PHONE_LIKE_PATTERN.sub(mask, text)
