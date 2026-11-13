"""The Embedder interface (a "port") and its fastembed adapter.

An embedder turns texts into vectors; similar meanings give nearby vectors.
Ingestion and (later) the retriever depend only on `Embedder`, so tests use a
fake one and never download a model.
"""

from typing import Protocol

from fastembed import TextEmbedding

from app.core.config import Settings


class Embedder(Protocol):
    """Any object with `dimensions` and `embed` is an Embedder (structural typing)."""

    # Length of every vector `embed` returns; must match the vector(N) column.
    dimensions: int

    def embed(self, texts: list[str]) -> list[list[float]]:
        """Return one vector per text, in the same order."""
        ...


class EmbeddingDimensionError(ValueError):
    """The model returned vectors of another size than EMBEDDING_DIMENSIONS."""


class FastEmbedEmbedder:
    """Embedder that runs a model locally with fastembed (ONNX runtime, no PyTorch).

    No API key and no per-call cost. The model files are downloaded once into
    `cache_dir` the first time this class is created.
    """

    def __init__(self, model_name: str, dimensions: int, cache_dir: str | None = None) -> None:
        self.dimensions = dimensions
        self._model_name = model_name
        self._model = TextEmbedding(model_name=model_name, cache_dir=cache_dir)

    def embed(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        # fastembed yields numpy arrays; plain lists keep numpy out of our interface.
        vectors = [vector.tolist() for vector in self._model.embed(texts)]
        for vector in vectors:
            if len(vector) != self.dimensions:
                raise EmbeddingDimensionError(
                    f"{self._model_name} returns {len(vector)} dimensions, but "
                    f"EMBEDDING_DIMENSIONS is {self.dimensions}. Change both together "
                    "(and the vector(N) column in a new migration)."
                )
        return vectors


def create_embedder(settings: Settings) -> Embedder:
    """Build the embedder configured by EMBEDDING_MODEL and EMBEDDING_DIMENSIONS."""
    return FastEmbedEmbedder(
        model_name=settings.embedding_model,
        dimensions=settings.embedding_dimensions,
        cache_dir=settings.embedding_cache_dir,
    )
