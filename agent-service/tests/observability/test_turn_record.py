"""log_turn without a database: the record it builds and its error handling."""

from decimal import Decimal
from uuid import uuid4

import pytest

from app.agent.deps import AgentDeps
from app.agent.intent import IntentResult
from app.agent.nodes import log_turn
from app.agent.state import AgentState, empty_memory, new_turn_input
from app.llm.client import LLMResponse
from app.observability.turn_log import TurnRecord
from tests.agent.agent_fakes import FakeGateway, FakeLLM, FakeRetriever


def finished_state(**overrides: object) -> AgentState:
    """The state at log_turn of a Spanish order-status turn with two LLM calls."""
    state = (
        new_turn_input(
            business_slug="joes-pizza",
            business_name="Joe's Pizza",
            default_language="en",
            conversation_id=str(uuid4()),
            message="¿Dónde está JP-1042? Mi teléfono es 555 123 4567",
        )
        | empty_memory()
    )
    state |= {
        "intent": IntentResult(
            intent="order_status", confidence=0.9, sentiment="neutral", language="es"
        ),
        "reply": "Tu pedido JP-1042 está en camino. Te llamamos al +1 555 123 4567.",
        "tool_calls": [
            {
                "name": "get_order_status",
                "args": {"order_code": "JP-1042", "phone_last4": "****4567"},
                "outcome": "found",
            }
        ],
        "llm_usage": [
            LLMResponse(
                text="{}", model="claude-haiku-5-5", prompt_tokens=900, completion_tokens=40
            ),
            LLMResponse(
                text="...", model="claude-haiku-5-5", prompt_tokens=300, completion_tokens=110
            ),
        ],
    }
    return state | overrides


def test_record_sums_tokens_prices_the_calls_and_computes_latency() -> None:
    state = finished_state()

    record = log_turn.build_turn_record(state, now=state["started_at"] + 1.234)

    assert record.business_id == "joes-pizza"
    assert str(record.conversation_id) == state["conversation_id"]
    assert record.intent == "order_status"
    assert record.language == "es"
    assert record.prompt_tokens == 1_200
    assert record.completion_tokens == 150
    assert record.cost_usd == Decimal("0.000195")
    assert record.model == "claude-haiku-5-5"
    assert record.latency_ms == 1_234
    assert record.tool_calls == state["tool_calls"]
    assert (record.blocked, record.handoff) == (False, False)


def test_record_masks_phone_numbers_in_the_reply() -> None:
    record = log_turn.build_turn_record(finished_state(), now=0.0)

    assert record.content == "Tu pedido JP-1042 está en camino. Te llamamos al ****4567."


def test_blocked_turn_has_no_intent_no_model_and_the_business_language() -> None:
    state = finished_state(intent=None, llm_usage=[], tool_calls=[], blocked=True, reply="No.")

    record = log_turn.build_turn_record(state, now=state["started_at"])

    assert record.blocked is True
    assert record.intent is None
    assert record.language == "en"
    assert record.model is None
    assert (record.prompt_tokens, record.completion_tokens, record.cost_usd) == (0, 0, 0)


def test_handoff_flag_is_copied() -> None:
    record = log_turn.build_turn_record(finished_state(handoff=True), now=0.0)

    assert record.handoff is True


class RecordingLogger:
    def __init__(self) -> None:
        self.records: list[TurnRecord] = []

    def log(self, record: TurnRecord) -> None:
        self.records.append(record)


class BrokenLogger:
    def log(self, record: TurnRecord) -> None:
        raise RuntimeError(f"database down while saving {record.content}")


def deps_with(turn_logger: object) -> AgentDeps:
    return AgentDeps(
        llm=FakeLLM([]), retriever=FakeRetriever([]), gateway=FakeGateway(), turn_logger=turn_logger
    )


def test_run_hands_the_record_to_the_turn_logger() -> None:
    turn_logger = RecordingLogger()

    assert log_turn.run(finished_state(), deps_with(turn_logger)) == {}
    assert len(turn_logger.records) == 1


def test_a_failing_logger_only_logs_a_warning_without_personal_data(
    caplog: pytest.LogCaptureFixture,
) -> None:
    assert log_turn.run(finished_state(), deps_with(BrokenLogger())) == {}

    [warning] = [r for r in caplog.records if r.name == "app.agent.nodes.log_turn"]
    assert warning.levelname == "WARNING"
    assert "RuntimeError" in warning.getMessage()
    assert "4567" not in warning.getMessage()


def test_the_default_logger_stores_nothing_and_does_not_fail() -> None:
    deps = AgentDeps(llm=FakeLLM([]), retriever=FakeRetriever([]), gateway=FakeGateway())

    assert log_turn.run(finished_state(), deps) == {}
