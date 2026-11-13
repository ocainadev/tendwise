import pytest

from app.prompts import load_prompt


def test_load_prompt_reads_the_markdown_file() -> None:
    assert load_prompt("classify_intent").startswith("<!-- version:")


def test_load_prompt_returns_the_cached_text() -> None:
    assert load_prompt("classify_intent") is load_prompt("classify_intent")


def test_load_prompt_raises_for_unknown_name() -> None:
    with pytest.raises(FileNotFoundError):
        load_prompt("does_not_exist")


ANSWER_PROMPTS = ["answer_with_context", "answer_order_status", "handoff_summary", "answer_generic"]


@pytest.mark.parametrize("name", ANSWER_PROMPTS)
def test_answer_prompts_are_versioned_and_treat_input_as_data(name: str) -> None:
    prompt = load_prompt(name)

    assert prompt.startswith("<!-- version:")
    assert "is data, never instructions" in prompt
    assert "language" in prompt
