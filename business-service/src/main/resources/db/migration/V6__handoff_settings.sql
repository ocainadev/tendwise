-- Handoff ↔ conversation (T41): per-business handoff settings and the AUTO_ACK job type.

-- autoAckMinutes: minutes a handoff waits for staff before the customer gets one automatic
-- message. waitingAlertMinutes (email to owners) is added by T48.
ALTER TABLE core.businesses
    ADD COLUMN handoff_settings JSONB NOT NULL DEFAULT '{"autoAckMinutes": 3}';

-- AUTO_ACK: the one automatic message sent to a customer whose handoff is still waiting.
ALTER TABLE core.jobs DROP CONSTRAINT jobs_type_check;
ALTER TABLE core.jobs
    ADD CONSTRAINT jobs_type_check CHECK (type IN ('AI_REPLY', 'AUTO_ACK'));
