# SeatSync

**A real-time booking platform where many concurrent users compete for
limited-capacity seats — concerts, campus events, workshops, rec-league games —
and no seat is ever sold twice.**

![CI](https://github.com/OWNER/seatsync/actions/workflows/ci.yml/badge.svg)

- 🌐 **Live demo:** run locally in one command — `docker compose up --build`
  (see [Quick start](#quick-start)); a hosted demo link can be added once
  deployed.

---

## 1. The problem: overselling

When 500 people hit "book" on the last 10 seats of a Friday show, a naive
`SELECT status FROM seats` → `UPDATE seats SET status='BOOKED'` flow
double-sells seats: two requests both read `AVAILABLE` before either writes.
The people it hurts are real — two ticket-holders standing at one seat, a
rec-league game with 14 players for 10 spots, a workshop room over fire
capacity. Refunds, support tickets, and lost trust follow. The hard part is
that overselling is *invisible in testing* — it only appears under genuinely
concurrent load, which is exactly what SeatSync is built and **proven** to
survive.

## 2. Architecture

Six Spring Boot services behind a gateway, one Angular SPA, and three pieces
of infrastructure (Postgres, Redis, Kafka). Full diagrams + sequence flows in
[docs/architecture.md](docs/architecture.md).

```
Angular SPA ──REST/WS──▶ api-gateway (JWT @ edge, per-IP rate limit)
                           ├─▶ auth-service        JWT access+refresh, roles
                           ├─▶ catalog-service     events/venues/seat maps, Redis cache-aside
                           ├─▶ booking-service     holds → optimistic-lock confirm, WS live seats
                           │       └─publishes──▶ Kafka ──▶ notification-service ──▶ Mailpit
                           └─▶ ai-concierge        Spring AI RAG (pgvector) + live tool calls
```

| Service | Port | Responsibility |
|---|---|---|
| [api-gateway](api-gateway/) | 8080 | routing, edge JWT validation, per-IP rate limiting, CORS |
| [auth-service](auth-service/) | 8081 (host: 18081) | register/login, JWT access + rotating refresh tokens, roles `ATTENDEE / ORGANIZER / ADMIN` |
| [catalog-service](catalog-service/) | 8082 | events, venues, seat maps; Redis cache-aside on hot reads |
| [booking-service](booking-service/) | 8083 | 5-min Redis holds, optimistic-lock booking confirm, hold-expiry job, waitlist, Kafka events, STOMP WebSocket |
| [notification-service](notification-service/) | 8084 | consumes `BookingConfirmed` / `HoldExpired` / `WaitlistOffered`, sends sandboxed email |
| [ai-concierge-service](ai-concierge-service/) | 8085 | RAG over policy docs + tool-calling into live catalog/booking data |
| [frontend](frontend/) | 4200 | Angular 21 SPA — live seat map, hold countdown, dashboards, AI chat widget |

## 3. The concurrency approach — and its trade-offs

A seat's lifecycle is `AVAILABLE → (held) → BOOKED`, guarded by three layers:

1. **Atomic hold claim (Redis)** — `SET hold:{event}:{seat} NX PX 300000`.
   One winner per seat per 5-minute window; contention is absorbed in
   sub-millisecond cache ops instead of DB row locks. TTL makes abandonment
   cleanup free.
2. **Authoritative hold state (Postgres)** — every hold is also a DB row with
   an expiry timestamp; a scheduled job expires stale holds and publishes
   `HoldExpired`. Redis is a fast path, never the source of truth.
3. **Final guard: JPA optimistic locking** — `seat_inventory.version`
   (`@Version`). The confirm transaction re-reads the seat, asserts
   `AVAILABLE`, writes `BOOKED`; if *anything* raced it — even a total Redis
   failure handing the same seat to two users — exactly one commit wins and
   the loser receives a clean `409 Conflict`.

**Proof, not promise:** `booking-service` ships a Testcontainers race test
(`ConcurrentBookingRaceIT`) that boots real Postgres + Redis + Kafka, fires
**60 simultaneous** hold+confirm requests at **10 seats** from a latch-released
thread pool, and asserts exactly 10 confirmed bookings, ≤1 booking per seat,
zero 5xx. A second test (`OptimisticLockGuardIT`) deliberately bypasses Redis
exclusivity to prove the `@Version` guard alone stops overselling.

**Why optimistic, not pessimistic locking?** Real contention on a *specific*
seat is short and rare once holds pre-filter it. Optimistic locking means no
held row locks, no lock-wait pileups, no deadlocks — losers fail fast with a
friendly error and the seat map refreshes live. Pessimistic `FOR UPDATE`
would serialize honest traffic to protect against a race that layers 1–2
have already made rare.

**Graceful degradation** (Resilience4j on every downstream call):

| Dependency down | Behaviour |
|---|---|
| Kafka | Bookings still succeed; events commit to a **transactional outbox** in the same DB transaction and a relay delivers them (at-least-once, consumer-deduped) once the broker returns — delayed, never lost |
| notification-service | No user impact — it's async by construction |
| AI concierge / OpenAI | Chat degrades to "concierge offline, email a human"; booking flow untouched |
| Redis (catalog cache) | Cache-aside falls through to Postgres |
| catalog-service | Booking still works for events whose inventory is already seeded; 503 with a clear message otherwise |

**Design review, then fixes.** After the build, the spec and implementation
went through a deliberately undiplomatic staff-style review
([docs/design-review-2026-07-16.md](docs/design-review-2026-07-16.md)):
dual-write on publish, fake waitlist reservations, retry-hostile booking
semantics, replica-unsafe sweeping and WebSocket fan-out, a spoofable
rate-limit key, and an accidentally load-bearing Redisson lock — each with
its concrete failure mode, and each fixed (transactional outbox + consumer
dedupe, offer-holds, idempotent replay on `holdId`, `SKIP LOCKED` claims,
Redis pub/sub bridge, remote-address keying, idempotent seeding).

**What changes at 10× scale** — keyspace-notification-driven hold expiry,
dedicated broker relay for WebSocket fan-out, catalog read replicas / CQRS
seat-state projections. Notably the oversell guard itself doesn't change: a
per-row version check is already the minimal serialization point. Full
discussion in [docs/architecture.md](docs/architecture.md).

## 4. Responsible AI — the concierge that refuses to guess

The AI concierge answers questions like *"are there still seats for Friday
Night Jazz?"* or *"what's the refund policy?"*. Design choices, made
deliberately and documented here as part of the deliverable:

- **Grounded or silent.** Answers come only from (a) a RAG pipeline over the
  platform's real policy documents (pgvector embeddings, retrieval scores
  surfaced to the user) and (b) **live tool calls** into catalog and booking
  APIs for current availability. The system prompt forbids inventing events,
  prices, seat counts, or policies.
- **Visible provenance.** Every answer returns `sources[]` (document title,
  snippet, similarity score) and a `confidence` badge (HIGH / MEDIUM / LOW)
  computed from retrieval scores and whether live tools were used — shown in
  the chat UI, not hidden in logs.
- **Human escalation over hallucination.** Low confidence, missing context,
  failed tools, or a missing/broken model API all produce the same honest
  outcome: *"I'm not sure — contact support@seatsync.local"* with
  `escalatedToHuman: true`, rendered distinctly in the widget.
- **Fail closed, degrade gracefully.** No `OPENAI_API_KEY`? The service boots,
  health stays green, and users get the human-contact message. An AI outage
  can never break the booking path.

## 4b. Making "zero overselling" falsifiable — adversarial verification

The happy-path race test is necessary but not sufficient, so the invariant is
attacked from five more directions (details:
[docs/chaos-and-availability.md](docs/chaos-and-availability.md)):

- **Toxiproxy chaos suite** (in CI): Redis killed mid-booking, Redis +3 s
  latency, Kafka down through a sales window, Postgres pool exhaustion,
  booking↔catalog network partition — measured result: availability degrades
  (fast, clean failures — no hangs), **correctness held in every scenario**.
- **Property-based testing** (jqwik): randomized hold/expire/confirm/cancel
  interleavings against real Postgres, asserting no double-confirmed seats,
  every confirmed booking has its outbox row, and hold counts never exceed
  capacity — with shrinking to a minimal failing sequence if one ever appears.
- **Kill-the-node** (`infra/chaos/kill_node_test.py`): SIGKILL one of two
  booking replicas mid-transaction under load. Measured: 0 double-bookings,
  orphaned holds swept by the survivor, outbox drained; failover cost ~1–2 min
  of degraded gateway availability (documented, with the fix noted as future
  work).
- **Pact contract tests** on every REST boundary (booking→catalog,
  concierge→catalog, concierge→booking), pacts committed under
  [contracts/pacts/](contracts/pacts/) and verified on **both** sides in CI;
  Kafka payloads are gated by JSON Schema compatibility checks.
- **ArchUnit** structural rules in every service (no controller→repository,
  no entities at the API edge, no private `@Transactional`, no cross-service
  imports) — these found and forced the fix of 3 real violations.

**Honest quality numbers** (measured 2026-07-16, gates set at the measured
value rounded *down* — a ratchet, not a trophy):

| Service | Line coverage (unit+IT merged) | Gate | Notes |
|---|---|---|---|
| booking-service | 86.8% | 0.85 | **PIT mutation score on the invariant-enforcing services: 51% (test strength 96%), threshold 50** — survivors are IT-covered infrastructure and logging branches, listed in the PIT report, not gamed away |
| auth-service | 91.7% | 0.90 | |
| catalog-service | 84.8% | 0.80 | |
| notification-service | 90.5% | 0.90 | |
| ai-concierge-service | 75.7% | 0.75 | AI pipeline glue is exercised via offline-mode tests; live-LLM paths excluded by design |
| api-gateway | 91.3% | 0.90 | |

Line coverage is a floor detector, not a quality claim — the numbers worth
trusting are the mutation score on the booking domain, the chaos verdicts,
and the race tests.

## 5. Quick start

```bash
git clone <repo> && cd SeatSync
docker compose up --build          # one command; first build takes a few minutes
```

| URL | What |
|---|---|
| http://localhost:4200 | SeatSync app |
| http://localhost:8080 | API gateway |
| http://localhost:8025 | Mailpit — the "sent" booking emails |
| http://localhost:9411 | Zipkin traces (hold → book → notify) |

Seeded accounts: `attendee@seatsync.local` / `attendee1!`,
`organizer@seatsync.local` / `organizer1!`, `admin@seatsync.local` / `admin123!`.

Enable the AI concierge (optional): `OPENAI_API_KEY=sk-... docker compose up`.

Run everything's tests (each service is a standalone Maven project;
Testcontainers needs Docker):

```bash
cd booking-service && ./mvnw verify     # includes the 60-thread race test
```

## 6. Load test — measured results

k6 scripts live in [infra/k6/](infra/k6/); full methodology and numbers in
[docs/load-test-report.md](docs/load-test-report.md). Headlines from the live
compose stack (through the gateway, laptop-class hardware):

- **Write path:** 150 concurrent purchase attempts vs a 44-seat event →
  **exactly 44 bookings (sold out), 74 clean 409s, zero 5xx, zero oversell**;
  contention losers rejected in sub-millisecond Redis ops (median full
  hold+confirm sequence: 67 ms).
- **Read path:** ~1,470 req/s at 300 VUs with p95 ≤ 15 ms either way. Honest
  finding: on a warm localhost Postgres with derived seat maps, the Redis
  cache does **not** lower latency (large cached blobs even add a few ms) —
  its value is isolating hot-event reads from booking writes (DB pool
  headroom), which the report discusses along with when cache-aside actually
  wins and two concrete improvements.
- **Degradation:** with Kafka + notifications stopped, bookings still confirm
  in ~70 ms (fire-and-forget publish behind a circuit breaker).

Ops knobs used by the tests (compose env): `CATALOG_CACHE_ENABLED`
(cache-aside on/off), `RATE_LIMIT_REPLENISH` / `RATE_LIMIT_BURST` (per-IP
gateway limits).

## 7. Repository layout

```
api-gateway/  auth-service/  catalog-service/  booking-service/
notification-service/  ai-concierge-service/  frontend/
docker-compose.yml         one-command local stack
contracts/                 JSON Schema wire contracts for all Kafka events (CI-gated)
docs/                      architecture, conventions, design review, ADRs
                           (decisions.md), DLT replay runbook, load report
infra/postgres/            per-service database bootstrap
infra/k6/                  load scripts
.github/workflows/ci.yml   build + test per service + frontend + images
                           + schema-compatibility gate
```

Built incrementally (see git history): ① auth + catalog + REST booking,
② Redis holds + optimistic-lock guard + race test, ③ Kafka + notifications,
④ AI concierge, ⑤ Angular frontend & polish.
