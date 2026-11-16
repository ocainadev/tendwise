"""agent-service's job queue (`agent.jobs`, T50) and the worker that runs it.

It works like business-service's `core.jobs` (docs/architecture.md, "Jobs"):

1. `enqueue` inserts a PENDING job, inside the caller's transaction, so the job
   exists only if the change that needs it was saved.
2. The worker takes the oldest due job with `FOR UPDATE SKIP LOCKED` (two
   instances never take the same job), sets it RUNNING, adds one to `attempts`
   and commits.
3. It runs the job's handler outside any transaction (embedding is slow; no
   lock is held meanwhile).
4. Success: DONE. An error: back to PENDING with a later `run_at` (5 s, then
   doubling) and `last_error` = the error type only. After the 3rd attempt the
   handler's `on_final_failure` runs and the job is FAILED.

A job left RUNNING for more than 5 minutes (the process died) goes back to
PENDING; its attempt still counts. One instance runs one job at a time: the
only job type today (INDEX_DOCUMENT) is CPU work, so running more at once
would not make it faster.
"""

import asyncio
import logging
from dataclasses import dataclass
from datetime import timedelta
from typing import Any, Protocol

import psycopg
from psycopg.types.json import Jsonb

from app.core.db import ConnectionFactory

logger = logging.getLogger(__name__)

MAX_ATTEMPTS = 3
RETRY_DELAY = timedelta(seconds=5)
STALE_AFTER = timedelta(minutes=5)
POLL_INTERVAL_SECONDS = 1.0


@dataclass(frozen=True)
class Job:
    """A job the worker has taken (status RUNNING)."""

    id: int
    type: str
    payload: dict[str, Any]
    attempts: int  # including the current one


class JobHandler(Protocol):
    """Runs one job type. Must be safe to run twice for the same job."""

    def run(self, job: Job) -> None:
        """Do the work. Returning means DONE (also when there was nothing to do)."""
        ...

    def on_final_failure(self, job: Job) -> None:
        """Called once after the last failed attempt, before the job becomes FAILED."""
        ...


def enqueue(conn: psycopg.Connection, job_type: str, payload: dict[str, Any]) -> int:
    """Insert a PENDING job due now and return its id (commits with the caller)."""
    row = conn.execute(
        "INSERT INTO agent.jobs (type, payload) VALUES (%s, %s) RETURNING id",
        (job_type, Jsonb(payload)),
    ).fetchone()
    assert row is not None  # RETURNING always gives a row
    return row[0]


def backoff(attempts: int) -> timedelta:
    """Wait before the next attempt: 5 s after the first, then twice as long each time."""
    return RETRY_DELAY * (2 ** (attempts - 1))


def error_type(error: BaseException) -> str:
    """What `last_error` and the logs keep: the class name, never the message."""
    return type(error).__name__


class JobWorker:
    """Takes due jobs from `agent.jobs` and runs the handler of their type."""

    def __init__(self, connect: ConnectionFactory, handlers: dict[str, JobHandler]) -> None:
        self._connect = connect
        self._handlers = handlers

    def run_next(self) -> bool:
        """Take and run one due job on this thread. False when no job was due.

        Tests call this directly; `run_forever` calls it in a loop.
        """
        self._release_stale()
        job = self._claim_next()
        if job is None:
            return False
        handler = self._handlers.get(job.type)
        try:
            if handler is None:
                raise LookupError(f"No handler for job type {job.type}")
            handler.run(job)
        except Exception as error:  # broad on purpose: any failure is a failed attempt
            self._record_failure(job, handler, error_type(error))
        else:
            self._finish(job.id, "DONE", None)
        return True

    async def run_forever(self) -> None:
        """Poll every second until cancelled (on shutdown).

        Each job runs in a thread (`asyncio.to_thread`), so the slow embedding
        work never blocks the requests the server is answering meanwhile.
        """
        while True:
            try:
                ran_a_job = await asyncio.to_thread(self.run_next)
            except Exception as error:  # e.g. the database is down: try again later
                logger.warning("Job worker poll failed: %s", error_type(error))
                ran_a_job = False
            if not ran_a_job:
                await asyncio.sleep(POLL_INTERVAL_SECONDS)

    def _release_stale(self) -> None:
        with self._connect() as conn:
            released = conn.execute(
                """
                UPDATE agent.jobs SET status = 'PENDING', locked_at = NULL
                WHERE status = 'RUNNING' AND locked_at < now() - %s
                """,
                (STALE_AFTER,),
            ).rowcount
        if released:
            logger.warning("Put %d stale RUNNING job(s) back to PENDING", released)

    def _claim_next(self) -> Job | None:
        """Mark the oldest due PENDING job RUNNING and commit, so no one else takes it."""
        with self._connect() as conn:
            row = conn.execute(
                """
                UPDATE agent.jobs
                SET status = 'RUNNING', locked_at = now(), attempts = attempts + 1
                WHERE id = (
                    SELECT id FROM agent.jobs
                    WHERE status = 'PENDING' AND run_at <= now()
                    ORDER BY run_at, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, type, payload, attempts
                """
            ).fetchone()
        if row is None:
            return None
        return Job(id=row[0], type=row[1], payload=row[2], attempts=row[3])

    def _record_failure(self, job: Job, handler: JobHandler | None, error: str) -> None:
        if job.attempts < MAX_ATTEMPTS:
            delay = backoff(job.attempts)
            logger.info(
                "Job %d (%s) attempt %d of %d failed: %s; retrying in %ds",
                job.id,
                job.type,
                job.attempts,
                MAX_ATTEMPTS,
                error,
                delay.total_seconds(),
            )
            with self._connect() as conn:
                conn.execute(
                    """
                    UPDATE agent.jobs
                    SET status = 'PENDING', locked_at = NULL, run_at = now() + %s, last_error = %s
                    WHERE id = %s
                    """,
                    (delay, error, job.id),
                )
            return
        logger.warning(
            "Job %d (%s) failed after %d attempts: %s", job.id, job.type, job.attempts, error
        )
        # The handler's fallback first, then FAILED: if the process dies in
        # between, the job comes back as stale and the fallback runs again.
        if handler is not None:
            try:
                handler.on_final_failure(job)
            except Exception as fallback_error:
                logger.error(
                    "Final-failure step of job %d failed: %s", job.id, error_type(fallback_error)
                )
        self._finish(job.id, "FAILED", error)

    def _finish(self, job_id: int, status: str, error: str | None) -> None:
        with self._connect() as conn:
            conn.execute(
                """
                UPDATE agent.jobs SET status = %s, locked_at = NULL, last_error = %s
                WHERE id = %s
                """,
                (status, error, job_id),
            )
