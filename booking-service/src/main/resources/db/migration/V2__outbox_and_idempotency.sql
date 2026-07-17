-- Review #1: transactional outbox — events are written in the same transaction
-- as the domain state change and relayed to Kafka asynchronously.
CREATE TABLE outbox_events (
    id           UUID PRIMARY KEY,
    topic        VARCHAR(128)             NOT NULL,
    message_key  VARCHAR(128)             NOT NULL,
    payload      TEXT                     NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    attempts     INTEGER                  NOT NULL DEFAULT 0
);

-- The relay scans only unpublished rows, oldest first.
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
-- The purge scans published rows by age.
CREATE INDEX idx_outbox_published ON outbox_events (published_at) WHERE published_at IS NOT NULL;

-- Review #4: one hold produces at most one booking — holdId is the natural
-- idempotency key for POST /api/bookings. NULL allowed for legacy rows;
-- Postgres UNIQUE permits multiple NULLs.
ALTER TABLE bookings ADD COLUMN hold_id UUID;
ALTER TABLE bookings ADD CONSTRAINT uq_bookings_hold_id UNIQUE (hold_id);
