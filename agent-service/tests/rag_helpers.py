"""Shared helpers for the RAG tests (fake embedder and pgvector constants)."""

import hashlib
import math
import re
from pathlib import Path

# Same image as docker compose, so the tests run against the real pgvector.
PGVECTOR_IMAGE = "pgvector/pgvector:0.8.0-pg16"
DIMENSIONS = 384
REPO_DATA_DIR = Path(__file__).resolve().parents[2] / "data"


class BagOfWordsEmbedder:
    """Fake embedder where texts sharing more words get closer vectors.

    Each word (3+ letters, lower case) is hashed to one of the dimensions 1..383
    and counted there; the vector is then scaled to length 1. So the cosine
    similarity of two texts grows with the words they share, and texts with no
    common words are (almost) orthogonal: similarity about 0. Dimension 0 is
    reserved for texts without any word, because an all-zero vector has no
    direction and pgvector cannot compute a cosine distance for it.
    It is deterministic and needs no model download.
    """

    dimensions = DIMENSIONS

    def embed(self, texts: list[str]) -> list[list[float]]:
        return [self._embed_one(text) for text in texts]

    def _embed_one(self, text: str) -> list[float]:
        vector = [0.0] * DIMENSIONS
        words = [word for word in re.findall(r"\w+", text.lower()) if len(word) >= 3]
        for word in words:
            bucket = 1 + int(hashlib.sha256(word.encode()).hexdigest(), 16) % (DIMENSIONS - 1)
            vector[bucket] += 1.0
        if not words:
            vector[0] = 1.0
        length = math.sqrt(sum(value * value for value in vector))
        return [value / length for value in vector]
