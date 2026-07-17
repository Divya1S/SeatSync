# Event schema contracts

`events/*.schema.json` (JSON Schema draft 2020-12) is the **wire contract**
for every Kafka event SeatSync produces. `events/examples/*.example.json` are
known-good payloads (captured from the real outbox) used by CI.

## Enforcement

1. **Producer tests** (booking-service) validate the payloads actually built
   by `BookingEventPublisher` against these schemas.
2. **Consumer tests** (notification-service) validate their test fixtures
   against the same schemas, so fixtures can't drift from reality.
3. **CI `contracts` job**: validates schemas + examples on every push, and on
   pull requests runs `.github/scripts/check_schema_compat.py` against the
   merge-base — **backward-incompatible changes fail the build**:
   removing a property, changing a property's `type`/`const`/`pattern`/
   `enum`/`format`, or adding to `required`. Adding optional properties is
   allowed.

## Evolution policy

- Need a new field? Add it as **optional** (not in `required`). Consumers
  tolerate unknown fields (`additionalProperties: true` + lenient Jackson).
- Need to rename/retype/require a field? That's a **new topic version**
  (`seatsync.<name>.v2`) with a migration window where both are produced.
- Delivery is **at-least-once** (transactional outbox, CONVENTIONS §6.3);
  every payload carries a unique `messageId` and consumers must dedupe on it.

## Why JSON Schema in-repo instead of Avro + Schema Registry

See [docs/decisions.md](../docs/decisions.md#schema-contracts).
