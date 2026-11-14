"""Where LangGraph saves the state of each conversation between turns (its "memory").

After every node LangGraph saves a checkpoint of the state under a
`thread_id`; we use the conversation id, so the next turn of the same
conversation starts from the saved state (history, pending order data, ...).

- Production: PostgreSQL (`PostgresSaver`), tables in the `agent` schema.
- Tests: in memory (`InMemorySaver`), nothing to set up.
"""

import psycopg
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.checkpoint.postgres import PostgresSaver
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer
from psycopg.rows import dict_row

# Our own Pydantic models stored in the state. LangGraph only rebuilds classes
# it was told about from a checkpoint (an unknown class could run unexpected
# code), so we list them explicitly as (module, class name).
STATE_CLASSES = [
    ("app.llm.client", "Message"),
    ("app.llm.client", "LLMResponse"),
    ("app.agent.intent", "IntentResult"),
    ("app.rag.retriever", "RetrievedChunk"),
    ("app.agent.state", "Source"),
    ("app.agent.staff", "StaffMessage"),
]


def make_serializer() -> JsonPlusSerializer:
    """LangGraph's default serializer, allowed to rebuild our state classes."""
    return JsonPlusSerializer(allowed_msgpack_modules=STATE_CLASSES)


def create_memory_checkpointer() -> InMemorySaver:
    """Checkpoints kept in a Python dict: for tests and local experiments."""
    return InMemorySaver(serde=make_serializer())


def create_postgres_checkpointer(database_url: str) -> PostgresSaver:
    """Checkpoints in PostgreSQL, in the `agent` schema. Call once at startup.

    `setup()` creates LangGraph's own tables (`checkpoints`, `checkpoint_blobs`,
    `checkpoint_writes`, `checkpoint_migrations`) if they are missing; it is safe
    to run on every start. `search_path=agent` makes them land in our schema
    instead of `public`. The connection settings (autocommit, no prepared
    statements, dict rows) are the ones PostgresSaver requires.
    """
    conn = psycopg.Connection.connect(
        database_url,
        autocommit=True,
        prepare_threshold=0,
        row_factory=dict_row,
        options="-c search_path=agent",
    )
    conn.execute("CREATE SCHEMA IF NOT EXISTS agent")
    checkpointer = PostgresSaver(conn, serde=make_serializer())
    checkpointer.setup()
    return checkpointer
