import pytest

from app.prompts import load_prompt


def test_load_prompt_reads_the_markdown_file() -> None:
    assert load_prompt("classify_intent").startswith("<!-- version:")


def test_load_prompt_returns_the_cached_text() -> None:
    assert load_prompt("classify_intent") is load_prompt("classify_intent")


def test_load_prompt_raises_for_unknown_name() -> None:
    with pytest.raises(FileNotFoundError):
        load_prompt("does_not_exist")
