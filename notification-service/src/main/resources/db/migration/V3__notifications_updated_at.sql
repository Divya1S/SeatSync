-- §6.5 retry-aware claims: a FAILED row is retried on redelivery, and a
-- SENDING claim older than 5 minutes is treated as a crashed attempt and
-- retried. Staleness is judged on updated_at, touched on every status
-- transition; backfill existing rows from created_at.
ALTER TABLE notifications
    ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE notifications
SET updated_at = created_at;

ALTER TABLE notifications
    ALTER COLUMN updated_at SET NOT NULL;
