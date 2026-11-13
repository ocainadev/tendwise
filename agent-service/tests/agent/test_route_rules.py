"""The deterministic routing rules, tested as a plain function."""

import pytest

from app.agent.intent import IntentResult
from app.agent.nodes.route import decide_route


def intent(name: str = "other", confidence: float = 0.9) -> IntentResult:
    return IntentResult(intent=name, confidence=confidence, sentiment="neutral", language="en")


@pytest.mark.parametrize(
    ("name", "step"),
    [
        ("catalog_question", "retrieve"),
        ("order_status", "ask_or_call_tool"),
        ("other", "answer_generic"),
    ],
)
def test_confident_intents_go_to_their_node(name: str, step: str) -> None:
    assert decide_route(intent(name), negative_streak=0) == (step, None)


@pytest.mark.parametrize(
    ("name", "reason"), [("complaint", "COMPLAINT"), ("human_request", "HUMAN_REQUEST")]
)
def test_complaint_and_human_request_hand_off(name: str, reason: str) -> None:
    assert decide_route(intent(name), negative_streak=0) == ("create_handoff", reason)


def test_low_confidence_hands_off_whatever_the_intent() -> None:
    assert decide_route(intent("complaint", 0.4), 0) == ("create_handoff", "LOW_CONFIDENCE")
    assert decide_route(intent("catalog_question", 0.59), 0) == (
        "create_handoff",
        "LOW_CONFIDENCE",
    )


def test_confidence_of_exactly_0_6_is_enough() -> None:
    assert decide_route(intent("catalog_question", 0.6), 0) == ("retrieve", None)


def test_second_negative_turn_in_a_row_hands_off() -> None:
    assert decide_route(intent("other"), negative_streak=1) == ("answer_generic", None)
    assert decide_route(intent("other"), negative_streak=2) == (
        "create_handoff",
        "NEGATIVE_SENTIMENT",
    )


def test_complaint_reason_wins_over_negative_streak() -> None:
    assert decide_route(intent("complaint"), negative_streak=3) == ("create_handoff", "COMPLAINT")
