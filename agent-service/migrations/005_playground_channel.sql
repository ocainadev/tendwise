-- Playground (T51): the knowledge page's test chat is stored with channel 'playground'.
-- The usage report leaves those conversations out.

ALTER TABLE agent.conversations DROP CONSTRAINT conversations_channel_check;
ALTER TABLE agent.conversations
    ADD CONSTRAINT conversations_channel_check
    CHECK (channel IN ('web', 'whatsapp', 'playground'));
