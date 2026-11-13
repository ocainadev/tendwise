import pytest

from app.agent.privacy import mask_phone_numbers


@pytest.mark.parametrize(
    ("text", "masked"),
    [
        ("Call +1 (555) 123-4567 now", "Call ****4567 now"),
        ("Meu número é 11 98765-4321.", "Meu número é ****4321."),
        ("555.123.4567", "****4567"),
        ("5551234567", "****4567"),
    ],
)
def test_phone_numbers_keep_only_the_last_four_digits(text: str, masked: str) -> None:
    assert mask_phone_numbers(text) == masked


@pytest.mark.parametrize(
    "text",
    [
        "Order JP-1042 is on its way",
        "ETA 2026-11-12 at 19:30",
        "Delivery costs $3.50",
        "Your code is ****5678",
    ],
)
def test_order_codes_dates_prices_and_masked_digits_are_kept(text: str) -> None:
    assert mask_phone_numbers(text) == text
