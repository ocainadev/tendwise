from collections.abc import Iterator

import numpy as np
import pytest

from app.core.config import Settings
from app.rag import embeddings
from app.rag.embeddings import EmbeddingDimensionError, FastEmbedEmbedder, create_embedder


class FakeTextEmbedding:
    """Stands in for fastembed's TextEmbedding, so no model is downloaded."""

    created_with: dict[str, str | None] = {}

    def __init__(self, model_name: str, cache_dir: str | None = None) -> None:
        FakeTextEmbedding.created_with = {"model_name": model_name, "cache_dir": cache_dir}
        self.size = 3

    def embed(self, texts: list[str]) -> Iterator[np.ndarray]:
        for index, _ in enumerate(texts):
            yield np.full(self.size, float(index))


@pytest.fixture(autouse=True)
def fake_fastembed(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(embeddings, "TextEmbedding", FakeTextEmbedding)


def test_embed_returns_plain_lists_in_order() -> None:
    embedder = FastEmbedEmbedder(model_name="some-model", dimensions=3)

    assert embedder.embed(["a", "b"]) == [[0.0, 0.0, 0.0], [1.0, 1.0, 1.0]]


def test_embed_of_no_texts_returns_no_vectors() -> None:
    assert FastEmbedEmbedder(model_name="some-model", dimensions=3).embed([]) == []


def test_wrong_vector_size_raises_a_clear_error() -> None:
    embedder = FastEmbedEmbedder(model_name="some-model", dimensions=384)

    with pytest.raises(EmbeddingDimensionError, match="EMBEDDING_DIMENSIONS is 384"):
        embedder.embed(["a"])


def test_factory_uses_the_configured_model(settings: Settings) -> None:
    embedder = create_embedder(settings)

    assert embedder.dimensions == 384
    assert FakeTextEmbedding.created_with == {
        "model_name": "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
        "cache_dir": ".cache/fastembed",
    }
