"""The runner: cases through the real graph with a fake LLM/gateway, and the fail-fast checks."""

import os
from pathlib import Path

import pytest

from evals.dataset import load_dataset
from evals.run_evals import (
    EXIT_CONFIG_ERROR,
    load_env_file,
    load_settings,
    main,
    run_cases,
)
from tests.agent.agent_fakes import (
    BUSINESS,
    CHUNK,
    FakeGateway,
    FakeLLM,
    FakeRetriever,
    classification,
    make_service,
)

CASES = {case.id: case for case in load_dataset()}


def test_cases_run_through_the_graph_and_are_scored() -> None:
    llm = FakeLLM(
        [
            # catalog-05: classifier, then the grounded answer.
            classification(intent="catalog_question"),
            "Delivery costs $3.99, and it is free for orders of $40.00 or more.",
            # order-01: classifier (with code + digits), then the status answer.
            classification(intent="order_status", order_code="JP-1043", phone_last4="0103"),
            "Your order JP-1043 is out for delivery and should arrive around 7:30 pm.",
            # guard-01 is blocked by guard_input: no LLM call.
        ]
    )
    gateway = FakeGateway()
    service = make_service(llm, FakeRetriever([CHUNK]), gateway)
    cases = [CASES["catalog-05"], CASES["order-01"], CASES["guard-01"]]

    scores = run_cases(service, gateway.get_business, cases)

    assert [score.failed_checks for score in scores] == [[], [], []]
    assert scores[1].outcome.tools == ["get_order_status"]
    assert scores[2].outcome.intent is None
    assert gateway.order_calls == [("joes-pizza", "JP-1043", "0103")]
    assert llm.replies == []


def test_an_error_in_one_case_does_not_stop_the_run() -> None:
    llm = FakeLLM([])  # the first LLM call raises IndexError
    service = make_service(llm)
    cases = [CASES["catalog-01"], CASES["guard-01"]]

    scores = run_cases(service, lambda slug: BUSINESS, cases)

    assert scores[0].outcome.error.startswith("IndexError")
    assert scores[1].failed_checks == []


def test_an_unknown_business_is_an_error_for_that_case() -> None:
    scores = run_cases(make_service(FakeLLM([])), lambda slug: None, [CASES["catalog-01"]])
    assert scores[0].outcome.error == "unknown business 'joes-pizza'"


def test_load_env_file_keeps_variables_already_set(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    env_file = tmp_path / ".env"
    env_file.write_text("# comment\nLLM_MODEL=from-file\nRAG_TOP_K='7'\nLLM_PROVIDER=\n")
    monkeypatch.setenv("LLM_MODEL", "from-shell")

    load_env_file(env_file)

    assert os.environ["LLM_MODEL"] == "from-shell"
    assert os.environ["RAG_TOP_K"] == "7"
    assert "LLM_PROVIDER" not in os.environ


def test_missing_api_key_fails_fast_with_a_clear_message(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setenv("DATABASE_URL", "postgresql://test@localhost/test")
    monkeypatch.setenv("BUSINESS_SERVICE_API_KEY", "test-key")

    assert main([]) == EXIT_CONFIG_ERROR
    assert "ANTHROPIC_API_KEY is not set" in capsys.readouterr().err


def test_missing_database_url_fails_fast(capsys: pytest.CaptureFixture[str]) -> None:
    assert main([]) == EXIT_CONFIG_ERROR
    assert "DATABASE_URL" in capsys.readouterr().err


def test_model_flag_overrides_the_configured_model(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("DATABASE_URL", "postgresql://test@localhost/test")
    monkeypatch.setenv("BUSINESS_SERVICE_API_KEY", "test-key")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key")

    assert load_settings(None).llm_model == "claude-haiku-5-5"
    assert load_settings("claude-sonnet-5-5").llm_model == "claude-sonnet-5-5"
