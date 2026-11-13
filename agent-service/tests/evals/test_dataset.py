"""The eval dataset itself: size, coverage, valid values, and facts grounded in real data."""

import re
from collections import Counter
from pathlib import Path
from typing import get_args

import pytest

from app.agent.replies import fixed_reply
from app.guardrails.injection import find_injection
from app.guardrails.normalize import normalize
from app.guardrails.replies import refusal
from evals.dataset import EvalCase, ToolName, load_dataset
from evals.scoring import contains_fact
from tests.rag_helpers import REPO_DATA_DIR

AGENT_DIR = Path(__file__).resolve().parents[2]
SEED_SQL = AGENT_DIR.parent / "business-service/src/main/resources/db/migration/V2__seed.sql"
BUSINESS_NAMES = {"joes-pizza": "Joe's Pizza", "denim-corner": "Denim Corner"}
# One seeded order: ('JP-1041', 'Alex Morgan', '+1 212-555-0101', ...
SEED_ORDER = re.compile(r"\('((?:JP|DC)-\d+)',\s*'[^']*',\s*'([^']+)'")
ORDER_CODE = re.compile(r"\b(?:JP|DC)-\d+\b")
LAST4 = re.compile(r"\b\d{4}\b")

CASES = load_dataset()


def business_docs(slug: str) -> str:
    """Every .md document of one business, normalised like the scoring does."""
    folder = REPO_DATA_DIR / "businesses" / slug
    return normalize("\n".join(path.read_text("utf-8") for path in sorted(folder.glob("*.md"))))


def seeded_orders() -> dict[str, str]:
    """Order code -> last 4 digits of the phone, from business-service's seed."""
    return {
        code: re.sub(r"\D", "", phone)[-4:]
        for code, phone in SEED_ORDER.findall(SEED_SQL.read_text("utf-8"))
    }


def test_has_45_cases_with_unique_ids() -> None:
    assert len(CASES) == 45
    assert len({case.id for case in CASES}) == 45


def test_coverage_matches_spec_section_10() -> None:
    assert Counter(case.category for case in CASES) == {
        "catalog": 15,
        "order_status": 10,
        "complaint_or_human": 8,
        "injection_or_off_topic": 7,
        "multilingual": 5,
    }


def test_multilingual_cases_cover_every_language() -> None:
    languages = {case.language for case in CASES if case.category == "multilingual"}
    assert languages == {"en", "es", "pt"}


def test_invalid_values_are_rejected() -> None:
    line = CASES[0].model_dump() | {"expected_intent": "pizza_question"}
    with pytest.raises(ValueError):
        EvalCase.model_validate(line)


def test_tool_names_are_the_ones_the_agent_logs() -> None:
    nodes = "".join(
        path.read_text("utf-8") for path in (AGENT_DIR / "app/agent/nodes").glob("*.py")
    )
    for tool in get_args(ToolName):
        assert f'"name": "{tool}"' in nodes


def test_cases_use_seeded_businesses() -> None:
    assert {case.business for case in CASES} == set(BUSINESS_NAMES)


def test_order_codes_exist_in_the_seed_for_that_business() -> None:
    orders = seeded_orders()
    prefixes = {"joes-pizza": "JP-", "denim-corner": "DC-"}
    for case in CASES:
        for code in ORDER_CODE.findall(case.input):
            assert code in orders, case.id
            assert code.startswith(prefixes[case.business]), case.id


def test_order_lookups_use_the_seeded_phone_except_the_not_found_case() -> None:
    orders = seeded_orders()
    for case in CASES:
        if case.expected_tool != "get_order_status":
            continue
        code = ORDER_CODE.search(case.input).group()
        last4 = LAST4.findall(case.input)[-1]
        expects_not_found = "could not find" in case.must_contain
        assert (orders[code] != last4) == expects_not_found, case.id


def test_injection_cases_are_exactly_the_ones_guard_input_blocks() -> None:
    for case in CASES:
        assert (find_injection(case.input) is not None) == case.is_injection, case.id


def test_catalog_facts_are_in_the_business_documents() -> None:
    """Grounded = taken from data/businesses; forbidden text must not be there."""
    for case in CASES:
        if case.expected_intent != "catalog_question":
            continue
        docs = business_docs(case.business)
        for fact in case.must_contain:
            assert contains_fact(docs, fact), (case.id, fact)
        for text in case.must_not_contain:
            assert not contains_fact(docs, text), (case.id, text)


def test_fixed_reply_facts_match_the_fixed_texts() -> None:
    """Handoff and refusal replies are fixed texts, so their facts must be in them."""
    for case in CASES:
        if case.expected_tool == "create_handoff":
            reply = fixed_reply("handoff", case.language)
        elif case.is_injection:
            reply = refusal("injection", case.language, BUSINESS_NAMES[case.business])
        else:
            continue
        for fact in case.must_contain:
            assert contains_fact(reply, fact), (case.id, fact)
