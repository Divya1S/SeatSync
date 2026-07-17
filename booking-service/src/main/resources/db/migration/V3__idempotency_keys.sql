-- Client-supplied Idempotency-Key support (conventions section 6.3): one row per
-- (user, endpoint, key) claim. The row is inserted BEFORE processing with the
-- response fields NULL ("in flight"); the final sub-500 status + body are stored
-- afterwards so replays return the original response without re-executing.
CREATE TABLE idempotency_keys (
    id              UUID PRIMARY KEY,
    user_id         UUID                     NOT NULL,
    endpoint        VARCHAR(64)              NOT NULL,
    idem_key        VARCHAR(255)             NOT NULL,
    request_hash    VARCHAR(64)              NOT NULL,
    response_status INTEGER,
    response_body   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_idempotency_user_endpoint_key UNIQUE (user_id, endpoint, idem_key)
);

-- The 24h purge (hold-expiry sweeper) scans by age.
CREATE INDEX idx_idempotency_created ON idempotency_keys (created_at);
