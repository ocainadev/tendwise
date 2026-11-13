from app.rag.chunking import chunk_markdown, split_sections, split_text

FAQ = """# FAQ

Intro line.

## Delivery

We deliver nearby.

### Fees

Delivery costs $3.99.

## Payment

Cards and cash.
"""


def test_sections_keep_the_heading_path() -> None:
    sections = split_sections(FAQ)

    assert [(section.path, section.text) for section in sections] == [
        (["FAQ"], "Intro line."),
        (["FAQ", "Delivery"], "We deliver nearby."),
        (["FAQ", "Delivery", "Fees"], "Delivery costs $3.99."),
        # A same-level heading replaces "Delivery" and drops its child "Fees".
        (["FAQ", "Payment"], "Cards and cash."),
    ]


def test_headings_without_text_are_skipped() -> None:
    sections = split_sections("# Title\n\n## Empty\n\n## Full\n\nSome text.")

    assert [section.path for section in sections] == [["Title", "Full"]]


def test_text_before_the_first_heading_has_an_empty_path() -> None:
    assert split_sections("Just text.")[0].path == []


def test_chunks_start_with_their_heading_and_store_it_in_metadata() -> None:
    chunks = chunk_markdown(FAQ)

    fees = chunks[2]
    assert fees.content == "FAQ > Delivery > Fees\n\nDelivery costs $3.99."
    assert fees.metadata == {"section": "FAQ > Delivery > Fees"}


def test_short_paragraphs_are_packed_into_one_chunk() -> None:
    assert split_text("First.\n\nSecond.", max_chars=100) == ["First.\n\nSecond."]


def test_pieces_never_exceed_the_size_limit() -> None:
    paragraphs = [f"Paragraph {n} " + "word " * 30 for n in range(10)]
    long_paragraph = "long " * 300  # one paragraph far above the limit

    pieces = split_text("\n\n".join([*paragraphs, long_paragraph]), max_chars=200, overlap=40)

    assert len(pieces) > 1
    assert all(len(piece) <= 200 for piece in pieces)


def test_a_word_longer_than_the_limit_is_cut() -> None:
    pieces = split_text("x" * 250, max_chars=100, overlap=0)

    assert pieces == ["x" * 100, "x" * 100, "x" * 50]


def test_neighbouring_pieces_overlap() -> None:
    first = "alpha " * 20  # 120 characters
    second = "beta " * 20
    pieces = split_text(f"{first}\n\n{second}", max_chars=200, overlap=30)

    assert len(pieces) == 2
    # The second piece starts with the last words of the first one.
    tail = pieces[1].split("\n\n")[0]
    assert tail.startswith("alpha")
    assert 0 < len(tail) <= 30
    assert pieces[0].strip().endswith(tail)


def test_overlap_is_dropped_when_it_would_break_the_limit() -> None:
    pieces = split_text(f"{'a ' * 50}\n\n{'b ' * 50}", max_chars=100, overlap=30)

    assert pieces[1].startswith("b")
    assert all(len(piece) <= 100 for piece in pieces)


def test_long_section_gives_several_chunks_with_the_same_section() -> None:
    markdown = "# Menu\n\n" + "\n\n".join(f"Item {n} " + "tasty " * 20 for n in range(20))

    chunks = chunk_markdown(markdown, max_chars=300, overlap=50)

    assert len(chunks) > 1
    for chunk in chunks:
        assert chunk.content.startswith("Menu\n\n")
        assert chunk.metadata == {"section": "Menu"}
        assert len(chunk.content) <= len("Menu\n\n") + 300


def test_empty_markdown_gives_no_chunks() -> None:
    assert chunk_markdown("") == []
