CREATE TABLE seat_inventory (
    id          UUID PRIMARY KEY,
    event_id    UUID           NOT NULL,
    seat_id     VARCHAR(64)    NOT NULL,
    section     VARCHAR(64)    NOT NULL,
    row_label   VARCHAR(16)    NOT NULL,
    seat_number INTEGER        NOT NULL,
    price       NUMERIC(10, 2) NOT NULL,
    status      VARCHAR(16)    NOT NULL,
    version     BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_seat_inventory_event_seat UNIQUE (event_id, seat_id)
);

CREATE INDEX idx_seat_inventory_event ON seat_inventory (event_id);

CREATE TABLE holds (
    id         UUID PRIMARY KEY,
    event_id   UUID                     NOT NULL,
    seat_id    VARCHAR(64)              NOT NULL,
    user_id    UUID                     NOT NULL,
    user_email VARCHAR(255)             NOT NULL,
    status     VARCHAR(16)              NOT NULL,
    price      NUMERIC(10, 2)           NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_holds_event_status ON holds (event_id, status);
CREATE INDEX idx_holds_status_expires ON holds (status, expires_at);
CREATE INDEX idx_holds_user ON holds (user_id);

CREATE TABLE bookings (
    id           UUID PRIMARY KEY,
    event_id     UUID                     NOT NULL,
    event_name   VARCHAR(255),
    seat_id      VARCHAR(64)              NOT NULL,
    user_id      UUID                     NOT NULL,
    user_email   VARCHAR(255)             NOT NULL,
    price        NUMERIC(10, 2)           NOT NULL,
    status       VARCHAR(16)              NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_bookings_event ON bookings (event_id);
CREATE INDEX idx_bookings_user ON bookings (user_id);

CREATE TABLE waitlist_entries (
    id         UUID PRIMARY KEY,
    event_id   UUID                     NOT NULL,
    user_id    UUID                     NOT NULL,
    user_email VARCHAR(255)             NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_waitlist_event_user UNIQUE (event_id, user_id)
);

CREATE INDEX idx_waitlist_event_created ON waitlist_entries (event_id, created_at);
