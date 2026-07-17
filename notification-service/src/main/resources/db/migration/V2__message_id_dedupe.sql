-- §6.4/§6.5: every event emission now carries a unique "messageId" (the outbox
-- row id). The consumer claims it by inserting the notifications row (status
-- SENDING) BEFORE sending; a duplicate delivery hits the unique constraint and
-- is skipped. NULL stays allowed for legacy messages without a messageId
-- (Postgres UNIQUE permits any number of NULLs).
ALTER TABLE notifications
    ADD COLUMN message_id UUID;

ALTER TABLE notifications
    ADD CONSTRAINT uq_notifications_message_id UNIQUE (message_id);

-- Widen the status enum with SENDING (row claimed, email not yet sent).
-- V1 declared status VARCHAR(16) with no CHECK, so no type change is needed;
-- codify the allowed values explicitly now that the enum is load-bearing.
ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_status
        CHECK (status IN ('SENDING', 'SENT', 'FAILED'));
