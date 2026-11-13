"""Split a markdown file into chunks small enough to embed and retrieve.

Steps: (1) split by headings, (2) split each section's text into pieces of at
most `max_chars`, packing whole paragraphs together, with a small overlap
between neighbouring pieces, (3) prefix each piece with its heading path so a
chunk still makes sense when the retriever returns it alone.

Pure functions (text in, chunks out), so they are easy to unit test.
"""

import re
from dataclasses import dataclass

MAX_CHUNK_CHARS = 800
OVERLAP_CHARS = 100
SECTION_SEPARATOR = " > "

# "## Delivery fees" -> level 2, title "Delivery fees"
HEADING_PATTERN = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")


@dataclass(frozen=True)
class Section:
    """The text under one heading. `path` holds every parent heading, top first."""

    path: list[str]
    text: str


@dataclass(frozen=True)
class Chunk:
    """One piece of a document, ready to embed. `content` starts with the heading path."""

    content: str
    metadata: dict[str, str]


def split_sections(markdown: str) -> list[Section]:
    """Split markdown by headings, keeping the path of parent headings.

    A heading of level N replaces the heading at level N and everything below
    it, so `# FAQ` / `## Delivery` / `### Fees` gives the path
    ["FAQ", "Delivery", "Fees"]. Headings with no text under them are dropped.
    """
    sections: list[Section] = []
    path: list[str] = []
    lines: list[str] = []

    def close_section() -> None:
        text = "\n".join(lines).strip()
        if text:
            sections.append(Section(path=list(path), text=text))
        lines.clear()

    for line in markdown.splitlines():
        match = HEADING_PATTERN.match(line)
        if match is None:
            lines.append(line)
            continue
        close_section()
        level = len(match.group(1))
        # Keep only the parents (levels above this one), then add this heading.
        path = path[: level - 1] + [match.group(2)]
    close_section()
    return sections


def split_text(
    text: str, max_chars: int = MAX_CHUNK_CHARS, overlap: int = OVERLAP_CHARS
) -> list[str]:
    """Split text into pieces of at most `max_chars`, keeping paragraphs whole when possible.

    Each new piece starts with the last words (up to `overlap` characters) of
    the previous one, so a sentence cut at a boundary is still found with its
    context. The overlap is skipped when it would make the piece too long.
    """
    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
    parts: list[str] = []
    for paragraph in paragraphs:
        parts.extend(_split_long_paragraph(paragraph, max_chars))

    pieces: list[str] = []
    current = ""
    for part in parts:
        candidate = f"{current}\n\n{part}" if current else part
        if len(candidate) <= max_chars:
            current = candidate
            continue
        pieces.append(current)
        tail = _last_words(current, overlap)
        with_overlap = f"{tail}\n\n{part}"
        current = with_overlap if tail and len(with_overlap) <= max_chars else part
    if current:
        pieces.append(current)
    return pieces


def chunk_markdown(
    markdown: str, max_chars: int = MAX_CHUNK_CHARS, overlap: int = OVERLAP_CHARS
) -> list[Chunk]:
    """Turn a whole markdown file into chunks with their heading path in metadata.

    `max_chars` limits the text under the heading; the heading line added at
    the top of each chunk comes on top of it.
    """
    chunks: list[Chunk] = []
    for section in split_sections(markdown):
        heading = SECTION_SEPARATOR.join(section.path)
        for piece in split_text(section.text, max_chars, overlap):
            content = f"{heading}\n\n{piece}" if heading else piece
            chunks.append(Chunk(content=content, metadata={"section": heading}))
    return chunks


def _split_long_paragraph(paragraph: str, max_chars: int) -> list[str]:
    """Split one paragraph longer than `max_chars` at spaces (words stay whole)."""
    if len(paragraph) <= max_chars:
        return [paragraph]
    parts: list[str] = []
    current = ""
    for word in paragraph.split():
        # A single "word" longer than the limit (e.g. a long URL) is cut by force.
        while len(word) > max_chars:
            if current:
                parts.append(current)
                current = ""
            parts.append(word[:max_chars])
            word = word[max_chars:]
        candidate = f"{current} {word}" if current else word
        if len(candidate) <= max_chars:
            current = candidate
        else:
            parts.append(current)
            current = word
    if current:
        parts.append(current)
    return parts


def _last_words(text: str, max_chars: int) -> str:
    """Return the end of `text`, at most `max_chars` long, without a cut word at the start."""
    if max_chars <= 0:
        return ""
    if len(text) <= max_chars:
        return text
    tail = text[-max_chars:]
    # Drop the (probably cut) first word, unless the tail is a single word.
    _, space, rest = tail.partition(" ")
    return rest.strip() if space else tail.strip()
