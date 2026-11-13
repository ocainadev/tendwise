"""Prompt files for the agent.

Every prompt lives in a `.md` file in this folder, never inline in code, so a
prompt change is a reviewable diff of plain text. The files sit inside the
`app` package, so the Docker image (which copies `app/`) ships them too.
"""

from functools import cache
from pathlib import Path

PROMPTS_DIR = Path(__file__).parent


@cache
def load_prompt(name: str) -> str:
    """Return the text of `app/prompts/{name}.md`.

    The file is read from disk only on the first call; later calls reuse the
    cached text. A missing file raises `FileNotFoundError` straight away, which
    is what we want for a typo in a prompt name.
    """
    return (PROMPTS_DIR / f"{name}.md").read_text(encoding="utf-8").strip()
