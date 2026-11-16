-- Assistant settings per business (T53, panel settings API).
-- {"name": "...", "tone": "...", "greeting": "...", "extraInstructions": "... (max 1,000 chars)",
--  "languages": ["en", "es", "pt"], "outOfHoursMessage": "..."}.
-- '{}' means nothing is set: the agent keeps its defaults and answers in en, es and pt.
ALTER TABLE core.businesses
    ADD COLUMN assistant_settings JSONB NOT NULL DEFAULT '{}';
