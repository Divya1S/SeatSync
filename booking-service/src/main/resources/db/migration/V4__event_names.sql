-- Durable event display names, written when inventory is seeded from catalog.
-- Confirm, expiry and waitlist flows read the name from here (after the in-memory
-- cache) instead of calling catalog inside a database transaction, and the
-- BookingConfirmed / HoldExpired / WaitlistOffered payloads can no longer carry an
-- empty eventName after a restart or a catalog outage (the event schemas require
-- minLength 1). Additive: a new table, no existing rows touched.
CREATE TABLE event_names (
    event_id   UUID PRIMARY KEY,
    name       VARCHAR(255)             NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
