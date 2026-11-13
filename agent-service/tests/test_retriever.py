"""Retriever against a real PostgreSQL + pgvector (needs Docker).

Both demo businesses are ingested with `BagOfWordsEmbedder`, a fake where
texts sharing words are close, so "similar" really means something here.
"""

from pathlib import Path

import psycopg
import pytest

from app.core.config import Settings
from app.core.db import connection_factory
from app.rag import retriever as retriever_module
from app.rag.embeddings import create_embedder
from app.rag.ingest import ingest_all
from app.rag.retriever import PgVectorRetriever, create_retriever
from tests.rag_helpers import BagOfWordsEmbedder

PIZZA_QUESTION = "How much does pizza delivery cost? Are there delivery fees?"


@pytest.fixture
def ingested(conn: psycopg.Connection, businesses_dir: Path) -> None:
    """Both businesses (joes-pizza and denim-corner) in the index."""
    ingest_all(conn, businesses_dir, BagOfWordsEmbedder())
    conn.commit()


def make_retriever(database_url: str, min_similarity: float = 0.0) -> PgVectorRetriever:
    """A retriever on the test database; threshold 0 keeps every row unless a test sets one."""
    return PgVectorRetriever(
        embedder=BagOfWordsEmbedder(),
        connect=connection_factory(database_url),
        min_similarity=min_similarity,
    )


@pytest.mark.usefixtures("ingested")
def test_a_pizza_question_never_returns_clothing_store_chunks(database_url: str) -> None:
    retriever = make_retriever(database_url)

    # top_k larger than joes-pizza's 16 chunks: even then nothing else comes back.
    chunks = retriever.retrieve("joes-pizza", PIZZA_QUESTION, top_k=50)

    assert chunks
    for chunk in chunks:
        assert chunk.source_path.startswith("joes-pizza/")
        assert "Denim" not in chunk.content


@pytest.mark.usefixtures("ingested")
def test_the_same_question_for_the_clothing_store_returns_only_its_chunks(
    database_url: str,
) -> None:
    retriever = make_retriever(database_url)

    chunks = retriever.retrieve("denim-corner", PIZZA_QUESTION, top_k=50)

    assert chunks
    for chunk in chunks:
        assert chunk.source_path.startswith("denim-corner/")
        assert "Pizza" not in chunk.content


@pytest.mark.usefixtures("ingested")
def test_the_best_chunk_comes_with_its_document_and_section(database_url: str) -> None:
    retriever = make_retriever(database_url)

    best = retriever.retrieve("joes-pizza", PIZZA_QUESTION)[0]

    assert best.source_path == "joes-pizza/faq.md"
    assert best.title == "Joe's Pizza – Frequently asked questions"
    assert best.section == "Joe's Pizza – Frequently asked questions > Delivery > Fees"
    assert "$3.99" in best.content
    assert 0.0 < best.similarity <= 1.0


@pytest.mark.usefixtures("ingested")
def test_top_k_is_respected(database_url: str) -> None:
    retriever = make_retriever(database_url)

    assert len(retriever.retrieve("joes-pizza", PIZZA_QUESTION, top_k=3)) == 3
    assert len(retriever.retrieve("joes-pizza", PIZZA_QUESTION, top_k=1)) == 1
    assert len(retriever.retrieve("joes-pizza", PIZZA_QUESTION)) == 5  # the default


@pytest.mark.usefixtures("ingested")
def test_results_are_sorted_best_first(database_url: str) -> None:
    retriever = make_retriever(database_url)

    chunks = retriever.retrieve("joes-pizza", PIZZA_QUESTION, top_k=10)

    similarities = [chunk.similarity for chunk in chunks]
    assert similarities == sorted(similarities, reverse=True)
    assert similarities[0] > similarities[-1]


@pytest.mark.usefixtures("ingested")
def test_chunks_under_the_threshold_are_dropped(database_url: str) -> None:
    everything = make_retriever(database_url).retrieve("joes-pizza", PIZZA_QUESTION, top_k=10)
    # A threshold between the best and the worst similarity must cut the list.
    threshold = (everything[0].similarity + everything[-1].similarity) / 2

    kept = make_retriever(database_url, threshold).retrieve("joes-pizza", PIZZA_QUESTION, top_k=10)

    assert 0 < len(kept) < len(everything)
    assert all(chunk.similarity >= threshold for chunk in kept)
    assert kept == [chunk for chunk in everything if chunk.similarity >= threshold]


@pytest.mark.usefixtures("ingested")
def test_an_off_topic_question_returns_nothing(database_url: str) -> None:
    retriever = make_retriever(database_url, min_similarity=0.3)

    assert retriever.retrieve("joes-pizza", "zebra quantum volcano") == []


@pytest.mark.usefixtures("ingested")
def test_an_unknown_business_returns_nothing(database_url: str) -> None:
    retriever = make_retriever(database_url)

    assert retriever.retrieve("no-such-business", PIZZA_QUESTION) == []


@pytest.mark.usefixtures("ingested")
def test_an_empty_question_returns_nothing(database_url: str) -> None:
    retriever = make_retriever(database_url)

    assert retriever.retrieve("joes-pizza", "   ") == []


def test_top_k_below_one_is_rejected() -> None:
    retriever = make_retriever("postgresql://unused")  # fails before connecting

    with pytest.raises(ValueError, match="top_k"):
        retriever.retrieve("joes-pizza", PIZZA_QUESTION, top_k=0)


def test_create_retriever_uses_the_settings(monkeypatch: pytest.MonkeyPatch) -> None:
    embedder = BagOfWordsEmbedder()
    monkeypatch.setattr(retriever_module, "create_embedder", lambda settings: embedder)
    settings = Settings(database_url="postgresql://unused", rag_min_similarity=0.42)

    retriever = create_retriever(settings)

    assert isinstance(retriever, PgVectorRetriever)
    assert retriever._min_similarity == 0.42
    assert retriever._embedder is embedder


@pytest.mark.slow
def test_real_model_keeps_relevant_chunks_and_drops_off_topic_ones(
    conn: psycopg.Connection, businesses_dir: Path, database_url: str
) -> None:
    """The calibrated RAG_MIN_SIMILARITY default with the real multilingual model."""
    settings = Settings(database_url=database_url)
    embedder = create_embedder(settings)
    ingest_all(conn, businesses_dir, embedder)
    conn.commit()
    retriever = PgVectorRetriever(
        embedder, connection_factory(database_url), settings.rag_min_similarity
    )

    delivery = retriever.retrieve("joes-pizza", "How much is delivery?")
    entrega = retriever.retrieve("denim-corner", "Quanto custa a entrega em São Paulo?")

    assert delivery[0].section == "Joe's Pizza – Frequently asked questions > Delivery > Fees"
    assert entrega[0].section == "Denim Corner – Perguntas frequentes > Entrega > Taxa"
    assert retriever.retrieve("joes-pizza", "Who won the world cup?") == []
    assert retriever.retrieve("denim-corner", "Who won the world cup?") == []
