"""Masking helpers, so personal data never reaches logs or stored tool calls."""


def mask_phone_last4(digits: str) -> str:
    """`5678` -> `****5678`: the format the spec uses for phone numbers in logs."""
    return f"****{digits}"
