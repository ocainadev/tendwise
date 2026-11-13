"""Retention limit on conversations (spec section 9: data minimisation).

Conversations hold what customers wrote, so we do not keep them forever: the
purge deletes those created more than CONVERSATION_RETENTION_DAYS ago.
"""

from uuid import UUID

from langgraph.checkpoint.base import BaseCheckpointSaver

from app.core.db import ConnectionFactory

# Turns go with their conversation (ON DELETE CASCADE).
DELETE_OLD_CONVERSATIONS = """
    DELETE FROM agent.conversations
    WHERE created_at < now() - make_interval(days => %(days)s)
    RETURNING id
"""


def delete_old_conversations(
    connect: ConnectionFactory, days: int, checkpointer: BaseCheckpointSaver | None = None
) -> int:
    """Delete conversations older than `days` days and return how many were deleted.

    With a `checkpointer`, the agent's memory of each one (its LangGraph thread,
    which holds the message history) is deleted too.
    """
    with connect() as conn, conn.transaction():
        rows = conn.execute(DELETE_OLD_CONVERSATIONS, {"days": days}).fetchall()
    deleted_ids: list[UUID] = [row[0] for row in rows]
    if checkpointer is not None:
        for conversation_id in deleted_ids:
            checkpointer.delete_thread(str(conversation_id))
    return len(deleted_ids)
