-- Runs once, when the database volume is created.
-- Schemas (core, agent) are created by each service's own migrations.
CREATE EXTENSION IF NOT EXISTS vector;
