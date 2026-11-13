"""The eval dataset: one JSON object per line in `evals/dataset.jsonl`.

Each case is one customer message sent to a fresh conversation, plus what we
expect the agent to do with it. Pydantic checks every line, so a typo in an
intent or a tool name fails loudly instead of silently scoring as a miss.
"""

from pathlib import Path
from typing import Literal

from pydantic import BaseModel, Field

from app.agent.intent import Intent, Language

DATASET_PATH = Path(__file__).parent / "dataset.jsonl"

# The two tools the agent can call (the "name" in TurnResult.tool_calls).
ToolName = Literal["get_order_status", "create_handoff"]

# The coverage groups of spec section 10, used to check the dataset's balance.
Category = Literal[
    "catalog",
    "order_status",
    "complaint_or_human",
    "injection_or_off_topic",
    "multilingual",
]


class EvalCase(BaseModel):
    id: str
    category: Category
    business: str  # business slug, e.g. "joes-pizza"
    language: Language  # the language of `input`, so also the expected reply language
    input: str
    # The intent the classifier should return. None means "guard_input must
    # block this message" (a prompt injection), so no intent is classified.
    expected_intent: Intent | None
    # The tool the agent should call, or None when it must call no tool.
    expected_tool: ToolName | None
    # Facts the reply must include / must not include (see scoring.py for how
    # they are matched). An entry "a|b" is satisfied by either alternative.
    must_contain: list[str] = Field(default_factory=list)
    must_not_contain: list[str] = Field(default_factory=list)

    @property
    def is_injection(self) -> bool:
        return self.expected_intent is None


def load_dataset(path: Path = DATASET_PATH) -> list[EvalCase]:
    """Read and validate every case; blank lines are skipped."""
    cases = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            cases.append(EvalCase.model_validate_json(line))
    return cases
