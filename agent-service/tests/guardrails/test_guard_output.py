"""guard_output: phone and e-mail masking, the length cap, and no customer names in tool data."""

from uuid import uuid4

import pytest

from app.agent.deps import AgentDeps
from app.agent.nodes import guard_output
from app.agent.state import empty_memory, new_turn_input
from app.gateways.business_gateway import OrderStatusInfo
from app.guardrails.output import (
    MAX_REPLY_CHARS,
    cap_length,
    make_reply_safe,
    mask_emails,
    mask_phones,
)
from tests.agent.agent_fakes import (
    BUSINESS,
    FakeGateway,
    FakeLLM,
    FakeRetriever,
    classification,
    make_service,
)

# --- phones ------------------------------------------------------------------


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        # US formats
        ("+1 (555) 010-0102", "****0102"),
        ("(555) 010-0102", "****0102"),
        ("555-010-0102", "****0102"),
        ("555.010.0102", "****0102"),
        ("5550100102", "****0102"),
        ("555 0100", "****0100"),
        # Brazilian formats
        ("+55 11 98765-4321", "****4321"),
        ("(11) 98765-4321", "****4321"),
        ("11 3333-4444", "****4444"),
        # Inside a sentence
        ("Call us at +1 (555) 010-0102 or 555-010-0199.", "Call us at ****0102 or ****0199."),
        ("Ligue para (11) 98765-4321, por favor.", "Ligue para ****4321, por favor."),
    ],
)
def test_phone_numbers_are_masked(text: str, expected: str) -> None:
    assert mask_phones(text) == expected


@pytest.mark.parametrize(
    "text",
    [
        "A large pizza is $14.00.",
        "O plano custa R$ 199,90 por mês.",
        "A TV custa R$ 1.299.000 na promoção.",
        "We are open 11:00–22:00, and 11:00 - 22:00 on Sundays.",
        "Your order JP-1042 is out for delivery.",
        "Order JP-1234567 is ready.",
        "It arrives on 2026-11-12 at 19:30.",
        "We have 12 pizzas and 3 sizes, 150 g each.",
        "Extension 0102, room 12-34.",  # fewer than 7 digits
    ],
)
def test_prices_times_order_codes_and_short_numbers_are_untouched(text: str) -> None:
    assert mask_phones(text) == text


# --- e-mails -----------------------------------------------------------------


def test_email_addresses_are_masked_but_the_domain_is_kept() -> None:
    assert mask_emails("Write to ana.souza+pizza@gmail.com today.") == (
        "Write to ****@gmail.com today."
    )
    assert mask_emails("mail: joe_99@mail.example.co.uk") == "mail: ****@mail.example.co.uk"


# --- length ------------------------------------------------------------------


def test_short_reply_is_not_cut() -> None:
    reply = "x" * MAX_REPLY_CHARS
    assert cap_length(reply) == reply


def test_long_reply_is_cut_at_a_sentence_boundary() -> None:
    sentence = "Our pizzas cost $14.00 each and come in three sizes. "  # 54 characters
    reply = sentence * 30  # 1,620 characters

    capped = cap_length(reply)

    assert len(capped) <= MAX_REPLY_CHARS
    assert capped.endswith("three sizes. …")
    assert capped.removesuffix(" …").endswith(".")


def test_long_reply_without_sentences_is_cut_at_a_space() -> None:
    capped = cap_length("word " * 300)

    assert len(capped) <= MAX_REPLY_CHARS
    assert capped.endswith("word …")


def test_masking_happens_before_the_cap() -> None:
    reply = "Call 5550100102. " + "Fine. " * 300
    assert make_reply_safe(reply).startswith("Call ****0102. Fine.")


# --- the node ----------------------------------------------------------------


def run_node(reply: str) -> dict:
    state = (
        new_turn_input(
            business_slug="joes-pizza",
            business_name="Joe's Pizza",
            default_language="en",
            conversation_id="c-1",
            message="hi",
        )
        | empty_memory()
    )
    state["reply"] = reply
    deps = AgentDeps(llm=FakeLLM([]), retriever=FakeRetriever([]), gateway=FakeGateway())
    return guard_output.run(state, deps)


def test_node_returns_nothing_when_the_reply_is_already_safe() -> None:
    assert run_node("Delivery is $3 inside the city.") == {}


def test_node_returns_the_masked_reply() -> None:
    assert run_node("Text me at +55 11 98765-4321.") == {"reply": "Text me at ****4321."}


# --- no customer names in tool data ------------------------------------------


def test_order_status_from_business_service_has_no_customer_data() -> None:
    # business-service never sends customer names or phones to the agent. This
    # allow-list fails if someone adds a customer field to the model.
    assert set(OrderStatusInfo.model_fields) == {
        "code",
        "status",
        "fulfilment",
        "eta",
        "updated_at",
    }


def test_tool_data_in_state_carries_no_name_field() -> None:
    llm = FakeLLM(
        [
            classification(intent="order_status", order_code="JP-1042", phone_last4="0102"),
            "Your order JP-1042 is on its way.",
        ]
    )
    service = make_service(llm)
    conversation_id = uuid4()

    service.handle_turn(conversation_id, BUSINESS, "Where is JP-1042? Phone ends in 0102")

    state = service._graph.get_state({"configurable": {"thread_id": str(conversation_id)}})
    [tool_call] = state.values["tool_calls"]
    # "name" at the top level is the tool's name; the arguments hold no personal data.
    assert tool_call["name"] == "get_order_status"
    assert tool_call["args"] == {"order_code": "JP-1042", "phone_last4": "****0102"}
    # The order data the LLM saw (inside its prompt) has no customer name either.
    prompt = llm.calls[1][1][0].content
    assert "name" not in prompt.split("<order_status>")[1].split("</order_status>")[0]
