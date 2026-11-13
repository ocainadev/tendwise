"""Fixes found by the real-model smoke test and evals (T27), with a fake LLM."""

from uuid import uuid4

import pytest

from app.agent.deps import AgentDeps
from app.agent.intent import MAX_SEARCH_QUERY_CHARS, IntentResult, phone_last4_in
from app.agent.nodes import retrieve
from app.agent.state import empty_memory, new_turn_input
from app.rag.retriever import RetrievedChunk
from tests.agent.agent_fakes import (
    BUSINESS,
    FakeGateway,
    FakeLLM,
    FakeRetriever,
    classification,
    make_service,
)

# --- phone digits in the same message as the order code ----------------------


@pytest.mark.parametrize(
    ("message", "digits"),
    [
        ("Where is my order JP-1042? Last digits 0102", "0102"),
        ("The last 4 digits of my phone are 0102", "0102"),
        ("Order JP-1041, phone ending 0199. Where is it?", "0199"),
        ("meu pedido JP-1042, final 0102", "0102"),
        ("Onde está meu pedido DC-2003? Os 4 últimos dígitos do telefone são 0203.", "0203"),
        ("Cadê meu pedido DC-2003? Termina em 0203", "0203"),
        ("¿Dónde está mi pedido JP-1043? Mi teléfono termina en 0103.", "0103"),
        ("mi pedido JP-1042, últimos dígitos 0102", "0102"),
    ],
)
def test_phone_last4_in_finds_digits_after_a_cue_word(message: str, digits: str) -> None:
    assert phone_last4_in(message) == digits


@pytest.mark.parametrize(
    "message",
    [
        "Where is my order JP-1042?",  # the digits of the order code are not phone digits
        "My phone is 555-0102",  # part of a longer number
        "Meu pedido DC-2003 atrasou",
        "I ordered 2 pizzas",
    ],
)
def test_phone_last4_in_ignores_other_numbers(message: str) -> None:
    assert phone_last4_in(message) is None


def test_order_status_uses_digits_the_classifier_missed() -> None:
    # The model gives the code but not the digits written in the same message.
    llm = FakeLLM(
        [classification(intent="order_status", order_code="JP-1042"), "It is on its way."]
    )
    gateway = FakeGateway()
    service = make_service(llm, gateway=gateway)

    result = service.handle_turn(uuid4(), BUSINESS, "Where is my order JP-1042? Last digits 0102")

    assert gateway.order_calls == [("joes-pizza", "JP-1042", "0102")]
    assert result.reply == "It is on its way."


def test_portuguese_order_status_with_final_digits_calls_the_tool() -> None:
    llm = FakeLLM(
        [
            classification(intent="order_status", language="pt", order_code="JP-1042"),
            "Seu pedido JP-1042 está a caminho.",
        ]
    )
    gateway = FakeGateway()
    service = make_service(llm, gateway=gateway)

    result = service.handle_turn(uuid4(), BUSINESS, "meu pedido JP-1042, final 0102")

    assert gateway.order_calls == [("joes-pizza", "JP-1042", "0102")]
    assert result.language == "pt"


def test_digits_are_not_guessed_for_other_intents() -> None:
    llm = FakeLLM([classification(intent="other"), "Hi!"])
    service = make_service(llm)
    conversation_id = uuid4()

    service.handle_turn(conversation_id, BUSINESS, "My last order was great, ending 2024 well")

    state = service._graph.get_state({"configurable": {"thread_id": str(conversation_id)}})
    assert state.values["pending_phone_last4"] is None


# --- search_query: cross-language retrieval -----------------------------------


def test_search_query_is_parsed_and_cleaned() -> None:
    base = {"intent": "catalog_question", "confidence": 0.9, "sentiment": "neutral"}

    parsed = IntentResult.model_validate(
        base | {"language": "es", "search_query": "  política de trocas  "}
    )
    assert parsed.search_query == "política de trocas"

    assert IntentResult.model_validate(base | {"language": "es"}).search_query is None
    assert (
        IntentResult.model_validate(base | {"language": "es", "search_query": " "}).search_query
        is None
    )
    assert (
        IntentResult.model_validate(base | {"language": "es", "search_query": 3}).search_query
        is None
    )
    long = IntentResult.model_validate(base | {"language": "es", "search_query": "a" * 500})
    assert long.search_query == "a" * MAX_SEARCH_QUERY_CHARS


def chunk(section: str, similarity: float) -> RetrievedChunk:
    return RetrievedChunk(
        content=f"Text of {section}",
        source_path="denim-corner/policies.md",
        title="Denim Corner – Políticas",
        section=section,
        similarity=similarity,
    )


class QueryRetriever:
    """Returns different chunks per query and records every query."""

    def __init__(self, by_query: dict[str, list[RetrievedChunk]]) -> None:
        self.by_query = by_query
        self.queries: list[str] = []

    def retrieve(self, business_slug: str, query: str, top_k: int = 5) -> list[RetrievedChunk]:
        self.queries.append(query)
        return self.by_query.get(query, [])


def retrieve_state(message: str, search_query: str | None) -> dict:
    state = new_turn_input("denim-corner", "Denim Corner", "pt", str(uuid4()), message)
    state |= empty_memory()
    state["intent"] = IntentResult(
        intent="catalog_question",
        confidence=0.9,
        sentiment="neutral",
        language="es",
        search_query=search_query,
    )
    return state


def deps_with(retriever: object, top_k: int = 2) -> AgentDeps:
    return AgentDeps(llm=FakeLLM([]), retriever=retriever, gateway=FakeGateway(), top_k=top_k)


def test_retrieve_also_searches_with_the_rewritten_query_and_merges() -> None:
    trocas = chunk("Trocas", 0.38)
    retriever = QueryRetriever(
        {
            "¿Cuál es la política de cambios?": [],  # under the threshold in Spanish
            "política de trocas": [trocas, chunk("Cancelamentos", 0.31), chunk("Prazos", 0.30)],
        }
    )
    state = retrieve_state("¿Cuál es la política de cambios?", "política de trocas")

    update = retrieve.run(state, deps_with(retriever))

    assert retriever.queries == ["¿Cuál es la política de cambios?", "política de trocas"]
    assert [c.section for c in update["chunks"]] == ["Trocas", "Cancelamentos"]  # top_k=2


def test_retrieve_keeps_one_copy_of_a_chunk_found_twice_with_the_best_score() -> None:
    retriever = QueryRetriever(
        {"Posso trocar?": [chunk("Trocas", 0.33)], "trocas": [chunk("Trocas", 0.53)]}
    )

    update = retrieve.run(retrieve_state("Posso trocar?", "trocas"), deps_with(retriever))

    assert [(c.section, c.similarity) for c in update["chunks"]] == [("Trocas", 0.53)]


def test_retrieve_searches_once_without_a_search_query() -> None:
    retriever = QueryRetriever({"Quanto custa?": [chunk("Jeans", 0.7)]})

    update = retrieve.run(retrieve_state("Quanto custa?", None), deps_with(retriever))

    assert retriever.queries == ["Quanto custa?"]
    assert [c.section for c in update["chunks"]] == ["Jeans"]


def test_spanish_question_about_portuguese_docs_gets_sources_in_the_graph() -> None:
    llm = FakeLLM(
        [
            classification(
                intent="catalog_question", language="es", search_query="política de trocas"
            ),
            "Puedes cambiar una prenda hasta 30 días después.",
        ]
    )
    retriever = FakeRetriever([chunk("Trocas", 0.38)])
    portuguese_business = BUSINESS.model_copy(update={"default_language": "pt"})
    service = make_service(llm, retriever=retriever)

    result = service.handle_turn(uuid4(), portuguese_business, "¿Cuál es la política de cambios?")

    assert [query for _, query, _ in retriever.calls] == [
        "¿Cuál es la política de cambios?",
        "política de trocas",
    ]
    assert [source.section for source in result.sources] == ["Trocas"]
    assert result.language == "es"
