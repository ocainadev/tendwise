"""Staff messages: what a person on the business's team told the customer.

During a handoff a person chats with the customer from the inbox. When the
conversation goes back to the AI, business-service sends those messages with
the next AI turn (`POST /internal/v1/replies`, T37) and they are added to the
conversation `history`, so the AI does not contradict what a person said.

This class lives in its own module (and not in `state.py`) so `intent.py` and
`prompt_input.py` can import it without an import cycle.
"""

from typing import Literal

from pydantic import BaseModel


class StaffMessage(BaseModel):
    """One staff message in the history.

    It is not an `app.llm.client.Message`: it is never sent to the LLM as a chat
    turn. The prompts only see it as a `Staff (<name>): ...` line of `<history>`.
    """

    role: Literal["staff"] = "staff"
    author: str  # the staff member's display name, as business-service sends it
    content: str
