CREATE TABLE notifications (
    id         UUID PRIMARY KEY,
    type       VARCHAR(64)  NOT NULL,
    recipient  VARCHAR(320) NOT NULL,
    subject    VARCHAR(512) NOT NULL,
    body       TEXT         NOT NULL,
    event_id   UUID,
    seat_id    VARCHAR(64),
    status     VARCHAR(16)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_notifications_created_at ON notifications (created_at DESC);
