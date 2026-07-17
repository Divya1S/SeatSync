# Design decisions (ADR-style, compact)

Deliberate choices where a well-known alternative was rejected — with the
reasoning, so future maintainers argue with the reasoning instead of
re-litigating from scratch.

## Outbox relay: polling publisher, not Debezium CDC

**Chosen:** in-service polling relay (`FOR UPDATE SKIP LOCKED` claim →
`KafkaTemplate.send().get()` → mark published; at-least-once).
**Rejected:** Debezium/CDC tailing the outbox table's WAL.

CDC is the right call when relay latency must be ~ms, polling load hurts, or
many services share the pattern. Here the poll is one indexed query per 2 s
per replica against a table that is almost always empty, the latency budget
for "confirmation email" is seconds, and Debezium brings Kafka Connect — a
whole new stateful runtime to deploy, monitor, and upgrade — into a stack a
single developer runs locally. The delivery guarantee (at-least-once, event
persisted in the same transaction as the domain change) is **identical**.
Revisit at the 10× mark or when a second service grows an outbox.

## Sweeper coordination: `SKIP LOCKED` row claims, not ShedLock

**Chosen:** every replica runs the sweeper; each expired hold is claimed
atomically (`UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED)`) so
exactly one replica processes any given hold, and replicas *divide* the work.
**Rejected:** ShedLock / leader election on the scheduled method.

ShedLock gives at-most-one *scheduler execution* via a lease — and a lease
that expires during a GC pause silently admits a second leader, which is
precisely the failure mode this codebase's own design review (#7) calls out
for lock-based mutual exclusion. Row claiming has no lease and no clock
assumptions: the claim *is* the transaction. It also scales out (N replicas
sweep in parallel on disjoint rows) where ShedLock serializes to one.
The requirement behind "use ShedLock" is *don't process a hold twice under
replicas* — that requirement is met with a strictly stronger mechanism, and
`SweeperClaimIT` proves it with two concurrent sweeps.

## Schema contracts: JSON Schema in-repo + CI gate, not Avro + Schema Registry

**Chosen:** JSON Schema (draft 2020-12) files in `contracts/events/`,
producer/consumer tests validating real payloads, and a CI job that rejects
backward-incompatible diffs on PRs.
**Rejected (for now):** Avro/Protobuf with Confluent Schema Registry.

A registry earns its keep with many producers/consumers, runtime schema
negotiation, and binary encodings. This system has one producer, one
consumer, human-debuggable JSON on the wire (invaluable in the DLT runbook),
and a repo where producer, consumer, and contract live together — so the
compatibility check belongs in CI, where a breaking change is stopped
*before merge* rather than at first produce. The enforced rules mirror
Registry BACKWARD mode: no removed fields, no type changes, no new required
fields. If a third consumer or a second producer appears, promote the same
schemas into a registry; the wire format doesn't change.

## Idempotency: layered, not header-only

`Idempotency-Key` header (stored request-hash + replayed response) is the
client-facing guarantee; beneath it, `bookings.hold_id UNIQUE` (natural-key
replay) and the hold state machine make double-execution structurally
impossible even for clients that don't send the header. The header protects
the *response contract*; the constraints protect the *data*.

## Kafka on the request path: never

Bookings/holds write to Postgres only (domain rows + outbox in one
transaction). Kafka being down, slow, or rebalancing cannot affect a sale —
verified live (booking in 70 ms with the broker stopped) and by
`KafkaDownGracefulIT` (event parked in the outbox, `published_at NULL`).
**At-least-once is the chosen delivery guarantee** end-to-end: outbox relay
retries until acked; consumers dedupe on `messageId`; DLT + documented replay
(runbook-dlt-replay.md) covers consumption failure.
