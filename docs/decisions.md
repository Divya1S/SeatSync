# Design decisions

Choices where a well-known alternative was rejected, with the reasoning, so
future maintainers can argue with the reasoning instead of starting over.

## Outbox relay: polling publisher, not Debezium CDC

**Chosen:** in-service polling relay (`FOR UPDATE SKIP LOCKED` claim →
`KafkaTemplate.send().get()` → mark published; at-least-once).
**Rejected:** Debezium/CDC tailing the outbox table's WAL.

CDC is the right call when relay latency must be ~ms, polling load hurts, or
many services share the pattern. Here the poll is one indexed query per 2 s
per replica against a table that is almost always empty, the latency budget
for "confirmation email" is seconds, and Debezium brings Kafka Connect, a
whole new stateful runtime to deploy, monitor and upgrade, into a stack a
single developer runs locally. The delivery guarantee (at-least-once, event
persisted in the same transaction as the domain change) is **identical**.
Revisit at the 10× mark or when a second service grows an outbox.

## Sweeper coordination: `SKIP LOCKED` row claims, not ShedLock

**Chosen:** every replica runs the sweeper; each expired hold is claimed
atomically (`UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED)`) so
exactly one replica processes any given hold, and replicas *divide* the work.
**Rejected:** ShedLock / leader election on the scheduled method.

ShedLock gives at-most-one *scheduler execution* via a lease, and a lease
that expires during a GC pause silently admits a second leader, which is
precisely the failure mode this codebase's own design review (#7) calls out
for lock-based mutual exclusion. Row claiming has no lease and no clock
assumptions: the claim *is* the transaction. It also scales out (N replicas
sweep in parallel on disjoint rows) where ShedLock serializes to one.
The requirement behind "use ShedLock" is *don't process a hold twice under
replicas*; that requirement is met with a strictly stronger mechanism, and
`SweeperClaimIT` proves it with two concurrent sweeps.

## Schema contracts: JSON Schema in-repo + CI gate, not Avro + Schema Registry

**Chosen:** JSON Schema (draft 2020-12) files in `contracts/events/`,
producer/consumer tests validating real payloads, and a CI job that rejects
backward-incompatible diffs on PRs.
**Rejected (for now):** Avro/Protobuf with Confluent Schema Registry.

A registry earns its keep with many producers/consumers, runtime schema
negotiation, and binary encodings. This system has one producer, one
consumer, human-debuggable JSON on the wire (invaluable in the DLT runbook),
and a repo where producer, consumer, and contract live together, so the
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

## Hold expiry: one transaction per hold, not one per sweep

**Chosen:** each expired hold is claimed (`LIMIT 1 FOR UPDATE SKIP LOCKED`),
flipped and outboxed in its own `REQUIRES_NEW` transaction; Redis release and
the WebSocket broadcast run after that commit; a failing hold is excluded for
the rest of the run and retried next sweep.
**Rejected:** one transaction for the whole batch.

A batch transaction has two failure modes the audit found: side effects were
emitted before commit (clients told a seat was free while it was still HELD if
the batch later rolled back), and any exception that crossed a transactional
proxy, for example from the waitlist offer, marked the batch rollback-only even
though it was caught, so one bad hold rolled back and re-ran the whole sweep
every 10 s. Per-hold transactions keep the claim/outbox atomicity that makes
the sweep replica-safe and exactly-once, and isolate failures. Cost: one
short transaction per expired hold instead of one per batch, bounded at 2,000
per run.

## Refresh tokens: rotation, family revocation, public logout

**Chosen:** rotation on every use (existing), plus: presenting an
already-rotated token revokes every live token of that user, and
`POST /api/auth/logout {refreshToken}` revokes the presented token and is
public (possession of the token is the credential), always answering 204.
**Rejected:** revocable access tokens.

Reuse of a rotated token is indistinguishable from a stolen token being
replayed after the legitimate client rotated it; revoking the family is the
OAuth 2.0 rotation guidance and costs a legitimate multi-tab user at most one
re-login, which the SPA avoids by re-reading the rotated token from storage
before refreshing. Access tokens stay irrevocable for their 15-minute life:
revoking them would require per-request state on every service, which the
stateless design trades away deliberately.

## Kafka on the request path: never

Bookings/holds write to Postgres only (domain rows + outbox in one
transaction). Kafka being down, slow, or rebalancing cannot affect a sale,
verified live (booking in 70 ms with the broker stopped) and by
`KafkaDownGracefulIT` (event parked in the outbox, `published_at NULL`).
**At-least-once is the chosen delivery guarantee** end-to-end: outbox relay
retries until acked; consumers dedupe on `messageId`; DLT + documented replay
(runbook-dlt-replay.md) covers consumption failure.
