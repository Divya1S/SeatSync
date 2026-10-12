# SeatSync

SeatSync is a reserved-seat booking platform for concerts, campus events,
workshops and rec-league games. Its one hard guarantee is that a seat is never sold
twice and an event never oversells, including under concurrent load and while
Redis, Kafka or a service replica is failing. The whole stack runs locally with
a single `docker compose up`.

## Features

- Event catalog with search and filters; seat maps derived from section layouts
- Five-minute seat holds with a live countdown; booking confirmation guarded by
  an optimistic lock
- Live seat state pushed over STOMP WebSockets, correct across service replicas
- Waitlist whose offers are real ten-minute holds that cascade to the next
  person when ignored
- Email notifications (booking confirmed, hold expired, waitlist offer) delivered
  through a transactional outbox, Kafka, idempotent consumers and a dead-letter
  topic
- Organizer dashboard with live sales and revenue
- JWT authentication with rotating refresh tokens, logout, role-based access and
  per-IP rate limiting at the gateway
- AI concierge: retrieval over the platform's policy documents plus live catalog
  and booking tools, returning sources, a confidence level and an explicit
  escalation to a human when it is unsure

## Tech stack

| Layer | Technologies |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Spring Cloud Gateway, Spring Data JPA, Spring Kafka, Spring AI 1.1, Resilience4j, Redisson, Flyway |
| Frontend | Angular 21, Angular Material, signals, STOMP over SockJS |
| Data and messaging | PostgreSQL 16 with pgvector, Redis 7, Kafka 3.9 (KRaft) |
| Observability | Micrometer tracing to Zipkin, structured logs, Spring Boot Actuator |
| Testing | JUnit 5, Mockito, Testcontainers, Toxiproxy, jqwik, Pact, ArchUnit, PIT, JaCoCo, Karma/Jasmine, k6 |
| Delivery | Docker Compose, GitHub Actions |

## Architecture

```
Angular SPA ──REST/WS──▶ api-gateway (JWT at the edge, per-IP rate limit, CORS)
                           ├─▶ auth-service          JWT access + refresh tokens, roles
                           ├─▶ catalog-service       events, venues, seat maps, Redis cache-aside
                           ├─▶ booking-service       holds → optimistic-lock confirm, waitlist, live seats
                           │       └─ outbox ──▶ Kafka ──▶ notification-service ──▶ SMTP (Mailpit)
                           └─▶ ai-concierge-service  Spring AI RAG (pgvector) + live tool calls
```

| Service | Port | Responsibility |
|---|---|---|
| [api-gateway](api-gateway/) | 8080 | routing, edge JWT validation, per-IP rate limiting, CORS |
| [auth-service](auth-service/) | 8081 (host 18081) | register, login, refresh rotation, logout; roles `ATTENDEE`, `ORGANIZER`, `ADMIN` |
| [catalog-service](catalog-service/) | 8082 | events, venues, derived seat maps; Redis cache-aside on hot reads |
| [booking-service](booking-service/) | 8083 | holds, confirm, cancel, waitlist, hold-expiry sweeper, transactional outbox, STOMP broadcasts |
| [notification-service](notification-service/) | 8084 | consumes `BookingConfirmed`, `HoldExpired`, `WaitlistOffered`; sends email with dedupe, retries and a DLT |
| [ai-concierge-service](ai-concierge-service/) | 8085 | RAG over policy documents plus tools against catalog and booking |
| [frontend](frontend/) | 4200 | Angular SPA served by nginx, which proxies `/api` and `/ws` to the gateway |

Each service owns its own PostgreSQL database. Commands and queries are
synchronous REST; facts that already happened travel over Kafka. Services never
import each other's code (enforced by ArchUnit); they share only the
conventions document, the JSON Schemas for events and the Pact contracts.
Diagrams and sequence flows are in [docs/architecture.md](docs/architecture.md).

## How overselling is prevented

A seat moves `AVAILABLE → held → BOOKED` behind three layers:

1. **Atomic hold claim in Redis.** `SET hold:{event}:{seat} NX PX 300000` gives
   one winner per seat per five-minute window and rejects the losers in a
   sub-millisecond cache operation, before any database transaction opens.
2. **Authoritative hold row in PostgreSQL.** Every hold also has a row with an
   expiry timestamp; confirm and the expiry sweeper read that timestamp, never
   the Redis TTL. Redis is a fast path, not the source of truth.
3. **Optimistic lock on the seat row.** The confirm transaction re-reads the
   seat, asserts `AVAILABLE` and writes `BOOKED` through a JPA `@Version`
   column. If anything raced it, even a Redis failure that handed the same seat
   to two users, exactly one commit wins and the other gets a clean `409`.

Optimistic locking was chosen over `SELECT ... FOR UPDATE` because the holds
make contention on a specific seat rare and brief: no row locks are held, there
are no lock-wait pileups or deadlocks, and losers fail fast while the seat map
refreshes live.

Two integration tests pin this down: `ConcurrentBookingRaceIT` fires 60
simultaneous hold-and-confirm requests at 10 seats against real PostgreSQL,
Redis and Kafka and asserts exactly 10 bookings and no 5xx responses;
`OptimisticLockGuardIT` bypasses Redis entirely, inserts 60 valid holds on one
seat and proves that only one confirm succeeds.

## Behaviour under failure

| Dependency down | Behaviour |
|---|---|
| Kafka | Bookings still succeed. Events are committed to a transactional outbox in the same database transaction and relayed once the broker returns (at-least-once, deduplicated by consumers). |
| notification-service | No user impact; it is asynchronous by construction. |
| AI concierge or OpenAI | Chat answers "the concierge is offline, email support" and flags the escalation; booking is untouched. |
| Redis | Catalog cache-aside falls through to PostgreSQL. In-flight confirms still succeed; new holds fail fast with a clean error until Redis returns. |
| catalog-service | Booking continues for events whose inventory is already seeded; otherwise a clear `503`. |
| A booking replica | Orphaned holds are swept by the survivor; outbox rows it had claimed are relayed by the survivor. |

Each of these was exercised with Toxiproxy and a kill-the-node run; the
measured results are in [docs/verification.md](docs/verification.md).

## AI concierge

The concierge answers questions such as "are there seats left for Friday Night
Jazz?" or "what is the refund policy?". Answers come only from the platform's
policy documents (pgvector embeddings) and live tool calls into catalog and
booking. Every response carries `sources[]` with retrieval scores, a
`confidence` of `HIGH`, `MEDIUM` or `LOW`, and `escalatedToHuman`. Low
confidence, missing context, failed tools or a missing API key all produce the
same outcome: "I'm not sure, contact support@seatsync.local". Without an
`OPENAI_API_KEY` the service still starts, health stays up and the booking path
is unaffected.

## Verification

Every service runs unit tests, Testcontainers integration tests, ArchUnit
structural rules and a JaCoCo line-coverage gate on `./mvnw verify`.
booking-service additionally runs the race tests, a Toxiproxy chaos suite,
jqwik property-based interleaving tests and PIT mutation testing. REST edges are
covered by Pact contracts verified on both sides; Kafka payloads are validated
against JSON Schemas with a CI gate that rejects backward-incompatible changes.

Measured on 2026-10-11 (laptop-class hardware, full Compose stack):

| Check | Result |
|---|---|
| Race test | 60 threads vs 10 seats: exactly 10 bookings, 50 clean `409`s, 0 5xx |
| k6 write storm | 150 purchase attempts vs a 44-seat event: 44 bookings, 74 `409`s, 0 5xx, median 67 ms per hold-and-confirm |
| k6 read path | about 1,470 req/s at 300 virtual users, p95 under 15 ms |
| Chaos scenarios | Redis down, Redis +3 s latency, Kafka down, PostgreSQL pool exhaustion, catalog partition (Toxiproxy, in CI) and a replica SIGKILL (manual): availability degraded, correctness held in every scenario |
| Line coverage (unit + integration) | booking 88.7%, auth 92.9%, catalog 86.9%, notification 90.8%, concierge 79.6%, gateway 93.1%; each gate is the measured value rounded down |
| PIT mutation score (booking services) | 72% (139 of 192 mutants killed), gate 70% |

Full methodology and numbers: [docs/verification.md](docs/verification.md).

## Prerequisites

- Docker with Compose v2 (the only requirement to run the stack)
- For development: JDK 21, Node.js 22 or newer, and Docker for the
  Testcontainers-based integration tests. Each service ships the Maven wrapper.

## Quick start

```bash
git clone https://github.com/Divya1S/SeatSync.git
cd SeatSync
docker compose up --build        # the first build takes a few minutes
```

| URL | What |
|---|---|
| http://localhost:4200 | SeatSync app |
| http://localhost:8080 | API gateway |
| http://localhost:8025 | Mailpit, the "sent" booking emails |
| http://localhost:9411 | Zipkin traces (hold → book → notify) |

Seeded accounts: `attendee@seatsync.local` / `attendee1!`,
`organizer@seatsync.local` / `organizer1!`, `admin@seatsync.local` / `admin123!`.

To enable the AI concierge, provide a key: `OPENAI_API_KEY=sk-... docker compose up`.

## Configuration

Every service reads `application.yml` with environment-variable overrides.
Defaults target local development; [.env.example](.env.example) lists every
variable. The ones that matter beyond a laptop:

| Variable | Default | Purpose |
|---|---|---|
| `JWT_SECRET` | development value (services log a warning while it is in use) | HS256 signing key shared by all services; set a private value of at least 32 bytes in any shared deployment |
| `OPENAI_API_KEY` | `sk-dummy-offline` | enables the AI concierge; otherwise it answers in offline mode |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200` | comma-separated SPA origins allowed by the gateway |
| `RATE_LIMIT_REPLENISH` / `RATE_LIMIT_BURST` | 20 / 40 | per-IP token bucket on data routes |
| `CHAT_RATE_LIMIT_REPLENISH` / `CHAT_RATE_LIMIT_BURST` | 2 / 5 | per-IP budget for the public chat endpoint |
| `TRUST_XFF` | `false` | use the first `X-Forwarded-For` value as the client key; only behind a trusted proxy |
| `HOLD_TTL` / `OFFER_TTL` | `PT5M` / `PT10M` | seat hold and waitlist-offer lifetimes |
| `MAX_ACTIVE_HOLDS_PER_USER` | 10 | active holds one account may have on one event |
| `CHAT_MEMORY_IDLE_TTL` / `CHAT_MEMORY_MAX_CONVERSATIONS` | `PT30M` / 10000 | bounds on the concierge's in-memory conversation store |
| `CATALOG_CACHE_ENABLED` | `true` | toggles the Redis cache-aside layer |
| `ADMIN_PASSWORD` | `admin123!` | password of the seeded admin account |

All services stop gracefully (20 s drain) and the six Java service containers
run as an unprivileged user. Access tokens live 15 minutes and are not
revocable; refresh tokens are rotated on use, revoked by `POST /api/auth/logout`,
and reuse of a rotated token revokes the user's whole token family.

## Running the tests

Each service is a standalone Maven project. Unit tests need only a JDK;
integration tests (`*IT`) start PostgreSQL, Redis, Kafka and Toxiproxy with
Testcontainers and need a running Docker daemon.

```bash
cd booking-service && ./mvnw test      # unit tests, ArchUnit, Pact consumer, schema contract
cd booking-service && ./mvnw verify    # plus race, chaos, property and outbox tests and the coverage gate
cd booking-service && ./mvnw -Ppit org.pitest:pitest-maven:mutationCoverage
cd frontend && npm ci && npm test && npm run build
```

Load tests (`infra/k6/`) and the kill-the-node script (`infra/chaos/`) run
against the live Compose stack; see [docs/verification.md](docs/verification.md).

## Repository layout

```
api-gateway/  auth-service/  catalog-service/  booking-service/
notification-service/  ai-concierge-service/  frontend/
docker-compose.yml         one-command local stack
.env.example               every environment variable with its default
contracts/                 JSON Schemas for Kafka events and Pact files for REST edges
docs/                      architecture, conventions, decisions, verification, runbook, reviews
infra/postgres/            per-service database bootstrap
infra/k6/                  load-test scripts
infra/chaos/               compose overlay and kill-the-node script
.github/workflows/ci.yml   build and test every service, frontend, schema gate, images
```

## Documentation

- [docs/architecture.md](docs/architecture.md): component and sequence diagrams, trade-offs, what changes at scale
- [docs/CONVENTIONS.md](docs/CONVENTIONS.md): the cross-service contract (ports, JSON shapes, topics, security rules, testing standards)
- [docs/decisions.md](docs/decisions.md): design decisions with the alternatives that were rejected
- [docs/verification.md](docs/verification.md): load-test and chaos results
- [docs/runbook-dlt-replay.md](docs/runbook-dlt-replay.md): inspecting and replaying the dead-letter topic
- [docs/design-review-2026-07-16.md](docs/design-review-2026-07-16.md): findings from the design review and the fixes that followed
- [docs/audit-2026-10-11.md](docs/audit-2026-10-11.md): codebase audit, fixes, verification and open issues
- [contracts/README.md](contracts/README.md): event schema contracts and the compatibility gate
- [frontend/README.md](frontend/README.md): developing, testing and building the SPA
