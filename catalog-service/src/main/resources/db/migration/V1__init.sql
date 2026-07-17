CREATE TABLE venues (
    id      UUID PRIMARY KEY,
    name    VARCHAR(255) NOT NULL,
    city    VARCHAR(255) NOT NULL,
    address VARCHAR(500) NOT NULL
);

CREATE TABLE events (
    id           UUID PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    category     VARCHAR(32)  NOT NULL,
    venue_id     UUID         NOT NULL REFERENCES venues (id),
    starts_at    TIMESTAMPTZ  NOT NULL,
    ends_at      TIMESTAMPTZ  NOT NULL,
    status       VARCHAR(32)  NOT NULL,
    organizer_id UUID         NOT NULL
);

CREATE INDEX idx_events_status ON events (status);
CREATE INDEX idx_events_category ON events (category);
CREATE INDEX idx_events_organizer_id ON events (organizer_id);
CREATE INDEX idx_events_starts_at ON events (starts_at);

CREATE TABLE sections (
    id            UUID PRIMARY KEY,
    event_id      UUID          NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    name          VARCHAR(64)   NOT NULL,
    price_tier    VARCHAR(32)   NOT NULL,
    price         NUMERIC(10,2) NOT NULL,
    row_count     INTEGER       NOT NULL,
    seats_per_row INTEGER       NOT NULL,
    CONSTRAINT uq_sections_event_name UNIQUE (event_id, name)
);

CREATE INDEX idx_sections_event_id ON sections (event_id);
