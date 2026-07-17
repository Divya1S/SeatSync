# SeatSync — Design Review (staff-level gap analysis)

Scope: the original product spec, cross-checked against the implementation as
built. Each finding states the verdict for the spec, the verdict for the code
that exists today, the concrete failure mode, and the remediation. Findings
are ordered by severity. Remediation status reflects the fixes landed as part
of this review.

---

## 1. Dual-write on `BookingConfirmed` — REAL (half already closed, half open)

**Spec:** "publishes BookingConfirmed on success" with no atomicity story —
both hazards (commit-without-publish, publish-without-commit) present.

**As built:** publish happens in a post-commit `TransactionSynchronization`,
so *publish-without-commit is impossible* — you can never notify about a
booking that doesn't exist. But the other half is fully open, by explicit
design ("fire-and-forget behind a circuit breaker"): if the broker is down or
the send times out **after** the DB commit, the event is dropped forever.

**Failure mode:** Kafka has a 5-minute wobble during an on-sale. Every
booking in that window: seat is BOOKED, money would be owed, `my-bookings`
shows the ticket — and no confirmation email is ever sent, no organizer
projection updates, and (worse, see #2) any waitlist offer emitted in that
window evaporates *after* the queue entry was consumed. There is no retry, no
record, no way to even enumerate what was lost. The circuit breaker makes
this *more* silent, not less: once open, sends aren't even attempted.

**Fix (landed): transactional outbox.** Events are written to an
`outbox_events` table inside the same DB transaction as the booking/hold
state change; an `OutboxRelay` polls (`FOR UPDATE SKIP LOCKED`, safe under
replicas), publishes with acks, marks `published_at`, and retries until the
broker accepts. Delivery becomes at-least-once ⇒ every event now carries a
unique `eventId` and the notification consumer dedupes on it (#9). The
"bookings survive Kafka outages" property is *strengthened*: the HTTP path no
longer touches Kafka at all, and events are delivered late instead of lost.

## 2. Waitlist — REAL in spec; domain was built, but the offer semantics were broken

**Spec:** names `WaitlistOffered` as a consumed event and never defines a
producer, a join API, a queue, or offer semantics. A consumer subscribed to
an event nobody sends is dead code by construction.

**As built:** the domain exists (join/leave endpoints, `waitlist_entries`,
oldest-first offer on expiry/cancel, event published). But three genuine
flaws remained:

- **The offer reserved nothing.** `offerExpiresAt` implied a 10-minute
  reservation; in reality the seat went straight back to general
  availability. Failure mode: seat frees, first-in-line gets the offer email,
  opens the app 30 seconds later — a random browser already bought it. The
  field was a lie.
- **The queue entry was deleted when the event was *emitted*.** Combined with
  #1's lossy publish: broker down at offer time ⇒ the customer is silently
  removed from the queue and never told anything. Data loss with no trace.
- **No cascade.** If the offeree ignored the offer, nothing moved to the next
  person in line.

**Fix (landed):** an offer now **creates a real 10-minute hold owned by the
waitlisted user** (reusing the entire existing hold machinery — Redis claim,
DB row, countdown in the UI via `GET /holds/mine`, WS broadcast), the
`WaitlistOffered` event carries the `holdId` and flows through the outbox,
and when the offer-hold expires, the standard sweeper frees the seat and
offers it to the *next* entry — the cascade falls out of the existing expiry
logic for free.

## 3. Hold/confirm race & owner check — spec was silent; the code is correct; here is the precise truth

**Spec:** does not say whether the hold is re-verified inside the confirm
transaction, nor whether ownership is checked. As a spec, that's a gap that
invites exactly the bug the question implies.

**As built:** all three sub-questions have good answers, and they're tested:

- **Ownership:** confirm rejects a hold owned by someone else with 403
  (`BookingServiceTest.holdOwnedBySomeoneElseIsRejectedWith403`). No
  authenticated user can confirm another user's hold.
- **Re-verification:** the hold row (status HELD, DB `expires_at` — not the
  Redis TTL) is checked inside the same `@Transactional` confirm that
  performs the seat write. Redis is never trusted for correctness.
- **The microsecond TTL-lapse window:** it exists and is *harmless*. If the
  hold expires between the check and the commit, a rival may claim a fresh
  hold and also reach the seat write — and the `@Version` optimistic lock
  admits exactly one `AVAILABLE→BOOKED` transition; the other party gets a
  clean 409. `OptimisticLockGuardIT` proves this brutally: 60 *simultaneously
  valid* holds on one seat → exactly 1 booking. The residual imperfection is
  fairness, not safety: in the lapse window, a "holder" can lose to a rival —
  a 409 with a fresh seat map, never an oversell.

One real interleaving remains post-#2: the sweeper can offer a seat to a
waitlisted user in the same instant the original holder's confirm commits;
the offeree's later confirm gets a clean 409. At-least-once offers are
acceptable; overselling is not. Documented, not "fixed" — fixing it would
serialize the sweeper against every confirm for zero correctness gain.

## 4. Idempotency on `POST /bookings` — REAL, but not the catastrophic version

**As built:** a retry cannot double-book — the hold state machine already
guards it (second confirm sees the hold is no longer HELD → 409; the seat
transition is single-shot regardless). The real defect is the *client
contract*: a client that got a 504 (e.g., gateway timeout) and retries
receives **409 for a booking that actually succeeded** — indistinguishable
from "someone sniped your seat." The user sees a scary error, refreshes,
maybe re-buys a different seat; any future payment integration that charges
per attempt would double-charge.

**Fix (landed): replay semantics on the natural idempotency key.** One hold
produces at most one booking, so `holdId` *is* the idempotency key — enforced
with a unique constraint on `bookings.hold_id`. Confirm on an
already-CONFIRMED hold by the same caller returns **200 with the original
booking** (a rival still gets 403/409). Same treatment for `POST /holds`:
re-requesting a seat you already hold returns 200 with your existing hold
instead of a self-inflicted 409. No client-supplied header needed; retries
are now safe end-to-end.

## 5. Expired-hold sweeper under replicas — REAL

**As built:** `fixedDelay` job on every instance, no coordination. With one
replica (the compose default) it's latent. Scale booking-service to 2+ and
each expired hold is processed up to N times concurrently: duplicate
`HoldExpired` events (⇒ duplicate emails, since the consumer had no dedupe —
#9), and after #2's fix it would be worse — two replicas could offer the
*same freed seat* to *two different* waitlisted users, guaranteeing one of
them a dead-end offer and consuming both queue entries.

**Fix (landed): per-row atomic claim, no global lock.** The sweep claims
expired holds with `UPDATE … SET status='EXPIRED' WHERE id IN (SELECT … FOR
UPDATE SKIP LOCKED)` and processes only the rows it won. N replicas divide
the work instead of duplicating it; no ShedLock/leader election dependency,
no lease-expiry failure mode (the claim is the transaction itself).

## 6. WebSocket fanout under replicas — REAL

**As built:** Spring's in-memory simple broker. Every broadcast goes only to
clients connected to the instance that processed the state change.
`docker compose up --scale booking-service=2` silently halves live-map
correctness: users on instance B watch a seat stay "available," click it,
and eat a 409 that the UI had no reason to expect. It fails *quietly* — the
worst kind.

**Fix (landed): Redis pub/sub bridge** (Redis is already in the stack — no
new infrastructure). Broadcasts publish to a Redis channel; every instance
subscribes and relays to its local simple broker, so each client receives
exactly one copy regardless of which instance produced the change. The
"replace simple broker with RabbitMQ relay at 10×" note in the architecture
doc stays valid; this closes the gap for realistic replica counts now.

## 7. Redisson lock honesty — the criticism is correct, and the code had a real (small) bug because of it

**Truth as demanded:** the Redisson lock is **not** mutual exclusion and is
**not load-bearing for correctness**. A lease that expires during a GC pause
admits a second seeder, full stop. The honest statement is: correctness comes
from `UNIQUE(event_id, seat_id)` plus the `@Version` guard; the lock is a
stampede-reduction optimization whose failure may only ever cost latency.

**Except — as built, that statement was accidentally false.** Seeding was
"if count == 0, bulk-insert all seats" *inside* the lock. Two seeders (lease
expiry, GC pause, Redis failover) ⇒ the second bulk insert hits the unique
constraint ⇒ **the user who triggered seeding gets a 500**. The failure cost
was correctness of the response, not just latency — precisely the
belt-and-braces illusion the question calls out.

**Fix (landed):** seat seeding is now idempotent (`ON CONFLICT DO NOTHING`
per row); a lost lock race converges to the same inventory and both requests
proceed. The lock remains as a stampede damper (the k6 storm's p95 tail — 50
VUs piling behind first-touch seeding — is that lock doing its actual job),
and the docs now state its role truthfully.

---

## Additional findings (not in the brief, surfaced during review)

**8. Rate-limiter key trusts client-supplied `X-Forwarded-For` — REAL,
security.** The gateway KeyResolver took the first XFF value; the gateway is
the *first* hop (no LB strips client headers). Any client bypasses per-IP
limiting entirely by randomizing XFF per request — or worse, pins a victim's
IP to exhaust *their* bucket. Fix (landed): resolve to the remote address by
default; `TRUST_XFF=true` opt-in for deployments genuinely behind a trusted
proxy.

**9. Notification consumer is at-least-once with no dedup — REAL.** Kafka
rebalances (and, after #1, outbox retries) redeliver; every redelivery was a
duplicate email. Fix (landed): all events carry `eventId`; notifications
claims `event_id` under a unique constraint *before* sending — duplicate
delivery becomes a logged no-op.

**10. First-boot topic-creation race — REAL, found during post-fix
integration.** On a pristine broker, notification-service's consumer
subscribed before booking-service's `KafkaAdmin` ran; with broker
auto-creation on, the subscription created the topics with **1 partition**,
KafkaAdmin then raised them to 3, and keyed messages hashed onto partitions
the consumer's generation-1 assignment didn't cover — invisible until the
~5-minute metadata refresh forced a rebalance. Pre-outbox this window *lost*
events; post-outbox it merely delayed them, but the failure mode (first
on-sale after a fresh deploy sends no emails for minutes) is real. Fix
(landed): broker auto-create **off**; both producer and consumer services
declare identical 3-partition `NewTopic` beans, making topic creation
idempotent and order-independent. Verified: pristine `down -v && up` now
delivers the confirmation email within seconds.

**11. Accepted/known limitations (explicitly not fixed, so nobody infers
they're free):** no payment/refund domain despite refund *policy* docs in the
AI corpus (the concierge can describe refunds the system cannot execute);
JWTs are irrevocable for their 15-min lifetime (revocation exists only for
refresh tokens); catalog cache can serve up to 600 s stale after a write if
Redis was down at eviction time (documented in the catalog builder's notes);
Zipkin tracing is fire-and-forget (spans drop silently if Zipkin is down —
acceptable); `docker compose` remains single-instance per service, so #5/#6
fixes are proven by tests rather than by the local topology.
