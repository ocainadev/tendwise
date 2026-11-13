# agent-service image: Python 3.12 slim + uv, runs as a non-root user.
FROM python:3.12-slim

# uv binary from the official image (pinned version, same as local development).
COPY --from=ghcr.io/astral-sh/uv:0.13.5 /uv /bin/uv

# Compile .pyc files at build time, copy packages instead of hard-linking them,
# use the image's Python instead of downloading one, and print logs right away.
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    PYTHONUNBUFFERED=1

WORKDIR /app

# Install dependencies first: this layer is cached until the lock file changes.
COPY pyproject.toml uv.lock .python-version ./
RUN uv sync --locked --no-dev

COPY app ./app
COPY migrations ./migrations
COPY scripts ./scripts

# The embedding model is downloaded into .cache/fastembed on first use (about
# 220 MB). Compose mounts a named volume there, so it is downloaded only once;
# the folder must exist and belong to appuser so the volume gets that owner.
RUN useradd --create-home --uid 10001 appuser \
    && mkdir -p /app/.cache/fastembed \
    && chown -R appuser /app/.cache
USER appuser

ENV PATH="/app/.venv/bin:$PATH"
EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
