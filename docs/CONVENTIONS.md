# SeatSync — Cross-Service Conventions & API Contracts

This document is the single source of truth for everything shared between
services. **Every service MUST follow it exactly** — ports, JSON shapes, topic
names, Redis keys, env vars. If something here conflicts with your instincts,
this document wins.

## 1. Version matrix (pinned, verified on Maven Central)

| Dependency | Version |
|---|---|
| Java | 21 (toolchain: `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`) |
| spring-boot-starter-parent | **3.5.16** |
| spring-cloud-dependencies | **2025.0.3** |
| spring-ai-bom | **1.1.8** (ai-concierge only) |
| org.redisson:redisson (plain client, no starter) | **3.52.0** (booking only) |
| io.jsonwebtoken jjwt-api / jjwt-impl / jjwt-jackson | **0.13.0** |
| resilience4j (via `io.github.resilience4j:resilience4j-spring-boot3`) | **2.3.0** |
| Testcontainers | Boot-managed (do NOT override the version) |

Maven, per service: standalone project (no shared parent module), parent =
`spring-boot-starter-parent:3.5.16`, `<java.version>21</java.version>`,
groupId `com.seatsync`, artifactId = service folder name. Copy the Maven
wrapper (`mvnw`, `.mvn/wrapper/`) already present in the service folder.

## 2. Service ports & base paths

| Service | Port | Base path(s) |
|---|---|---|
| api-gateway | 8080 | routes below |
| auth-service | 8081 | `/api/auth` |
| catalog-service | 8082 | `/api/catalog` |
| booking-service | 8083 | `/api/holds`, `/api/bookings`, `/api/events/*` (booking views), `/ws` |
| notification-service | 8084 | `/api/notifications` |
| ai-concierge-service | 8085 | `/api/concierge` |
| frontend (nginx) | 4200 | `/` |
| PostgreSQL | 5432 | — |
| Redis | 6379 | — |
| Kafka | 9092 (in-network `kafka:9092`), host `localhost:29092` | — |
| Mailpit | 1025 SMTP / 8025 UI | — |
| Zipkin | 9411 | — |

Base paths are implemented **in controllers** (`@RequestMapping("/api/auth")`),
NOT via `server.servlet.context-path`, so the gateway can pass paths through
unmodified (no StripPrefix anywhere).

Package root per service: `com.seatsync.<auth|catalog|booking|notification|gateway|concierge>`.

## 3. Configuration & environment variables

All config lives in `application.yml` with env-var overrides using Spring's
relaxed binding. Defaults target **local dev** (localhost infra); Docker
Compose overrides via env vars.

Common env vars (same names everywhere):

```
SPRING_DATASOURCE_URL      (default jdbc:postgresql://localhost:5432/<db>)
SPRING_DATASOURCE_USERNAME (default seatsync)
SPRING_DATASOURCE_PASSWORD (default seatsync)
SPRING_DATA_REDIS_HOST     (default localhost)
SPRING_DATA_REDIS_PORT     (default 6379)
SPRING_KAFKA_BOOTSTRAP_SERVERS (default localhost:29092; compose: kafka:9092)
JWT_SECRET                 (default seatsync-dev-secret-change-me-0123456789abcdef)
MANAGEMENT_ZIPKIN_TRACING_ENDPOINT (default http://localhost:9411/api/v2/spans)
```

Databases (one per service, single Postgres instance, image `pgvector/pgvector:pg16`):
`seatsync_auth`, `seatsync_catalog`, `seatsync_booking`,
`seatsync_notification`, `seatsync_ai`. User/password: `seatsync`/`seatsync`.

Schema management: **Flyway** (`src/main/resources/db/migration/V1__init.sql`),
`spring.jpa.hibernate.ddl-auto: validate`. `ddl-auto: update` is FORBIDDEN.
Exception: ai-concierge lets Spring AI initialize the pgvector store
(`spring.ai.vectorstore.pgvector.initialize-schema: true`) and uses Flyway
only if it has its own tables.

**Migration policy (expand/contract — rolling deploys must never break):**
every migration must be safe while the *previous* app version is still
running against the new schema. Concretely: (1) additive changes only in the
same release as the code that uses them — new tables, new NULLABLE columns
(or defaulted), new indexes `CREATE INDEX CONCURRENTLY`-equivalent; (2) never
rename or change a column's type in place — add the new column, dual-write in
code, backfill, switch reads, then drop the old column **at least one release
later** (the "contract" step); (3) `NOT NULL` on an existing column only
after a backfill migration and only when the previous release already writes
it; (4) destructive statements (DROP/ALTER TYPE) require a comment in the
migration naming the release that made them safe. Existing V1/V2/V3
migrations are additive and comply.

Every service exposes actuator: `management.endpoints.web.exposure.include: health,info,metrics`
and `management.endpoint.health.probes.enabled: true`. Health must NOT require auth.

Structured logging: add `logging.structured.format.console: ${LOG_FORMAT:}` —
empty locally (human-readable), compose sets `LOG_FORMAT=ecs`.
Tracing (all Java services): deps `io.micrometer:micrometer-tracing-bridge-brave`
+ `io.zipkin.reporter2:zipkin-reporter-brave`; `management.tracing.sampling.probability: 1.0`.

## 4. Security / JWT

- HS256, shared secret `JWT_SECRET` (min 32 bytes). jjwt 0.13.0
  (`Jwts.SIG.HS256`, key via `Keys.hmacShaKeyFor(secret.getBytes(UTF_8))`).
- Claims: `sub` = userId (UUID string), `email`, `name` (full name),
  `roles` = JSON array of `"ATTENDEE" | "ORGANIZER" | "ADMIN"` (no `ROLE_` prefix),
  `typ` = `"access"` or `"refresh"`, `iss` = `"seatsync-auth"`.
- Access token TTL 15 min; refresh 7 days.
- Gateway validates signature/expiry/typ=access at the edge and forwards the
  `Authorization` header unchanged. **Each service ALSO validates the JWT
  itself** (defense in depth): a small `OncePerRequestFilter` that parses the
  token and populates `SecurityContext` with authorities `ROLE_<role>`.
- In controllers, read identity from the authenticated principal — convention:
  the filter sets a `JwtUser` record `(UUID id, String email, String name, List<String> roles)`
  as the Authentication principal.
- Public (no JWT required): `POST /api/auth/register|login|refresh`,
  `GET /api/catalog/**`, `GET /api/events/*/seats` (booking, works anonymously),
  `/ws/**`, `POST /api/concierge/chat`, `/actuator/health`.
  Everything else requires a valid access token.
- WebSocket auth: the frontend connects to `/ws` (SockJS) without JWT —
  subscriptions are read-only broadcasts, no user data. Do not block it.

## 5. Error format

RFC 7807 via Spring's `ProblemDetail`. All 4xx/5xx bodies:

```json
{ "type": "about:blank", "title": "Conflict", "status": 409,
  "detail": "Seat A-1-4 is already held", "instance": "/api/holds" }
```

Use `@RestControllerAdvice` + `ResponseEntity<ProblemDetail>`. Key statuses:
400 validation, 401 missing/invalid token, 403 role, 404 not found,
409 seat conflict / optimistic-lock loss / expired hold.

## 6. Domain model & API contracts

IDs are UUIDs (strings in JSON). Timestamps are ISO-8601 UTC instants
(`2026-07-16T18:00:00Z`). Money is a JSON number (BigDecimal, 2dp).
Enums are UPPERCASE strings. JSON field names are camelCase.

**Seat ID format**: `"<section>-<row>-<number>"`, e.g. `"A-3-12"` — section
name, row label, seat number joined by `-`. This string is THE seat identity
shared by catalog, booking, and frontend.

### 6.1 auth-service

```
POST /api/auth/register  {email, password, fullName, role}   role ∈ ATTENDEE|ORGANIZER (403 for ADMIN)
  → 201 {id, email, fullName, roles: ["ATTENDEE"]}           409 duplicate email
POST /api/auth/login     {email, password}
  → 200 {accessToken, refreshToken, tokenType: "Bearer", expiresInSeconds: 900,
         user: {id, email, fullName, roles}}                 401 bad creds
POST /api/auth/refresh   {refreshToken}
  → 200 same shape as login (rotates refresh token)          401 invalid/revoked
GET  /api/auth/me        (auth) → 200 {id, email, fullName, roles}
```

Passwords BCrypt-encoded. Refresh tokens persisted (table `refresh_tokens`,
store SHA-256 hash, rotate + revoke on use). Seed on startup (idempotent):
admin `admin@seatsync.local` / `admin123!` (env `ADMIN_PASSWORD`), demo
organizer `organizer@seatsync.local` / `organizer1!`, demo attendee
`attendee@seatsync.local` / `attendee1!`.

### 6.2 catalog-service

Venue: `{id, name, city, address}`

Event (full):
```json
{ "id": "…", "name": "…", "description": "…",
  "category": "CONCERT|WORKSHOP|SPORTS|CAMPUS|OTHER",
  "venue": {"id": "…", "name": "…", "city": "…", "address": "…"},
  "startsAt": "…", "endsAt": "…", "status": "DRAFT|PUBLISHED|CANCELLED",
  "organizerId": "…", "totalSeats": 120,
  "priceFrom": 25.00, "priceTo": 99.00 }
```

Seat map (`GET /api/catalog/events/{id}/seatmap`):
```json
{ "eventId": "…",
  "sections": [ { "name": "A", "priceTier": "STANDARD|PREMIUM|VIP", "price": 49.00,
      "rows": [ { "label": "1",
          "seats": [ {"seatId": "A-1-1", "number": 1, "price": 49.00} ] } ] } ] }
```

```
GET  /api/catalog/events?q=&category=&city=&from=&to=&page=0&size=20   public, PUBLISHED only
  → 200 {content: [Event], totalElements, totalPages, number, size}
GET  /api/catalog/events/{id}           public (any status for owner/admin, PUBLISHED for others/anon) — Redis cache-aside
GET  /api/catalog/events/{id}/seatmap   public — Redis cache-aside
GET  /api/catalog/events/mine           ORGANIZER → all own events incl. DRAFT
POST /api/catalog/events                ORGANIZER  → 201 Event
  body {name, description, category, venueId, startsAt, endsAt,
        sections: [{name, rowCount, seatsPerRow, priceTier, price}]}
  (service expands sections → seat map; row labels are "1".."rowCount")
PUT  /api/catalog/events/{id}           owner or ADMIN (metadata only, not seat map)
POST /api/catalog/events/{id}/publish   owner or ADMIN → status PUBLISHED
DELETE /api/catalog/events/{id}         owner or ADMIN → status CANCELLED (soft)
GET  /api/catalog/venues                public;  POST /api/catalog/venues  ORGANIZER
```

Redis cache-aside: keys `catalog:event:{id}` and `catalog:seatmap:{id}`,
TTL 600s, JSON values, **delete on any write** to that event. Use
`StringRedisTemplate` + Jackson explicitly (visible cache-aside, not
`@Cacheable` magic). Cache misses must fall through gracefully if Redis is
down (try/catch → hit DB).

Seed data on startup (idempotent, only if venues table empty): 2 venues,
4 PUBLISHED demo events (owned by the seeded organizer's UUID — resolve by
convention below) with small seat maps (e.g. 2 sections × 5 rows × 10 seats),
one event named **"Friday Night Jazz"** starting next Friday 20:00 UTC.
Seeded organizer UUID: auth seeds users with random UUIDs, so catalog seed
uses a **fixed** organizerId `00000000-0000-0000-0000-000000000002`; auth-service
MUST seed organizer@seatsync.local with exactly this UUID, admin with
`…-000000000001`, attendee with `…-000000000003`.

### 6.3 booking-service

Tables: `seat_inventory (id uuid pk, event_id, seat_id, section, row_label,
seat_number, price, status AVAILABLE|BOOKED, version bigint — @Version,
unique(event_id, seat_id))`, `holds (id, event_id, seat_id, user_id,
user_email, status HELD|CONFIRMED|EXPIRED|RELEASED, price, expires_at,
created_at)`, `bookings (id, event_id, event_name, seat_id, user_id,
user_email, price, status CONFIRMED|CANCELLED, confirmed_at)`,
`waitlist_entries (id, event_id, user_id, user_email, created_at,
unique(event_id, user_id))`.

Redis: hold key `hold:{eventId}:{seatId}`, value = JSON
`{"holdId":"…","userId":"…","expiresAt":"…"}`, **TTL 300s**, created with
`SET NX` semantics (atomic claim). Redisson lock `lock:inventory:{eventId}`
only around one-time inventory seeding from catalog.

Flow — `POST /api/holds {eventId, seatId}` (auth):
1. Ensure inventory seeded: if no rows for eventId, take Redisson lock,
   re-check, fetch event + seatmap from catalog via **OpenFeign** wrapped in
   Resilience4j circuit breaker `catalog` + retry (3 attempts, 200ms backoff);
   insert seat rows; event must be PUBLISHED. Fallback: if catalog is down and
   no inventory exists → 503 ProblemDetail "Event catalog temporarily unavailable".
2. Seat row must be status AVAILABLE (else 409).
3. `SET hold:{e}:{s} NX PX 300000` — if key exists → 409 "already held".
4. Insert `holds` row (HELD, expiresAt = now+5min). Broadcast WS seat state HELD.
→ 201 `{holdId, eventId, seatId, userId, expiresAt, status: "HELD"}`

Flow — `POST /api/bookings {holdId}` (auth):
1. Load hold; must belong to caller, status HELD, not expired (DB timestamp is
   authoritative; also delete the Redis key as part of confirm).
2. **Final guard**: load seat_inventory row, assert AVAILABLE, set BOOKED,
   `saveAndFlush` — `@Version` optimistic lock; catch
   `ObjectOptimisticLockingFailureException` → 409 "Seat was just taken".
3. Insert booking (CONFIRMED), mark hold CONFIRMED, delete Redis key.
4. Publish `BookingConfirmed` to Kafka (fire-and-forget; failure is logged,
   NEVER fails the booking — resilience4j circuitbreaker `kafka-publish` +
   try/catch). Broadcast WS seat state BOOKED.
→ 201 `{bookingId, eventId, eventName, seatId, price, status: "CONFIRMED", confirmedAt}`

Scheduled job every 10s: claim expired holds ATOMICALLY so N replicas never
process the same hold twice — `UPDATE holds SET status='EXPIRED' WHERE id IN
(SELECT id FROM holds WHERE status='HELD' AND expires_at < now() FOR UPDATE
SKIP LOCKED) RETURNING …`; for each claimed hold: delete Redis key, write
`HoldExpired` to the outbox, broadcast WS AVAILABLE, then offer the seat to
the oldest waitlist entry (if any). **A waitlist offer = a real system-created
hold owned by the waitlisted user** (10-min TTL, Redis key + holds row like
any hold, WS broadcast HELD): write `WaitlistOffered` (incl. `holdId`) to the
outbox and remove the entry. When an offer-hold expires, the same sweep
naturally cascades to the next entry. Booking cancel (`DELETE
/api/bookings/{id}`, owner only): seat → AVAILABLE (optimistic-locked
update), booking → CANCELLED, WS broadcast, same waitlist offer logic.

**Transactional outbox (replaces fire-and-forget Kafka sends):** table
`outbox_events (id uuid pk, topic, message_key, payload text/jsonb,
created_at, published_at nullable, attempts int default 0)`. Domain services
write events to this table INSIDE the same transaction as the state change.
`OutboxRelay` (fixedDelay 2s): claim a batch of unpublished rows with `FOR
UPDATE SKIP LOCKED`, send via KafkaTemplate with ack wait (~5s timeout), set
`published_at` on success, increment `attempts` and leave the row on failure
(retried next poll; WARN log every 10th attempt). Delete published rows older
than 7 days during the sweep. Delivery is at-least-once; consumers dedupe on
`eventId` (§6.4). Bookings/holds NEVER touch Kafka on the request path.

**Idempotent replay:** `bookings.hold_id` is UNIQUE — one hold, at most one
booking; `holdId` is the natural idempotency key. `POST /bookings {holdId}`
where the hold is already CONFIRMED and the existing booking belongs to the
caller → **200** with the original booking body (rival callers still get
403/409). `POST /holds` where the caller already has the active hold on that
seat → **200** with the existing hold (others still 409). Clients may safely
retry both endpoints after timeouts.

**Idempotency-Key header (client-supplied, `POST /api/holds` and
`POST /api/bookings`):** optional header, 1–255 chars, scoped to
(user, endpoint). Table `idempotency_keys (id, user_id, endpoint, idem_key,
request_hash sha256-hex of the raw body, response_status, response_body text,
created_at, unique(user_id, endpoint, idem_key))`. Semantics:
- First request: process normally, then store the final status+body
  (including error responses with status < 500).
- Replay (same key, same request_hash): return the stored status+body
  unchanged, plus header `Idempotency-Replayed: true`. Do NOT re-execute.
- Same key, different request_hash → **422** ProblemDetail
  "Idempotency-Key reused with a different request body".
- Concurrent duplicate (row exists but response not yet stored) → **409**
  ProblemDetail "original request still in progress" (client retries later).
- Rows are purged after 24 h by the existing sweeper. 5xx responses are NOT
  stored (client retry re-executes; the natural-key replay above still makes
  that safe). The natural `holdId` replay remains as defense in depth.

**Inventory seeding is idempotent:** per-row `ON CONFLICT (event_id, seat_id)
DO NOTHING`. The Redisson lock around seeding is a stampede-reduction
optimization ONLY — correctness comes from the unique constraint + @Version;
a lost/expired lock may cost latency, never correctness.

**WS fanout under replicas:** broadcasts go through Redis pub/sub channel
`seatsync:seat-events` (JSON `{destination, payload}`); every instance
subscribes and relays to its local simple broker. Producers never send to the
local broker directly (each client gets exactly one copy).

```
POST   /api/holds                    (auth) → 201 above; 409 conflict
DELETE /api/holds/{holdId}           (auth, owner) → 204, seat AVAILABLE again, WS broadcast
GET    /api/holds/mine               (auth) → [{holdId, eventId, seatId, expiresAt}]
POST   /api/bookings {holdId}        (auth) → 201 above
GET    /api/bookings/mine            (auth) → [{bookingId, eventId, eventName, seatId, price, status, confirmedAt}]
DELETE /api/bookings/{id}            (auth, owner) → 204
GET    /api/events/{eventId}/seats   PUBLIC → {eventId, seats: [{seatId, status: AVAILABLE|HELD|BOOKED, mine, holdExpiresAt?}]}
                                     ("mine" true only for caller's active holds; anonymous → all mine=false;
                                      HELD computed by overlaying active holds on inventory)
GET    /api/events/{eventId}/stats   (ORGANIZER or ADMIN) → {eventId, totalSeats, available, held, booked, revenue}
POST   /api/events/{eventId}/waitlist  (auth) → 201 {position}; DELETE → 204
```

WebSocket: Spring `spring-boot-starter-websocket`, STOMP over SockJS at
endpoint `/ws` (allowed origins `*`), simple broker, topic
**`/topic/events/{eventId}/seats`**, message:
`{"eventId":"…","seatId":"A-1-1","status":"AVAILABLE|HELD|BOOKED","at":"<instant>"}`.

### 6.4 Kafka

Bootstrap: env above. Producer: JSON via `JsonSerializer` (no type headers:
`spring.kafka.producer.properties.spring.json.add.type.headers: false`).
Consumers use `StringDeserializer` + manual Jackson to a `Map`/DTO keyed by
the `type` field. Topics are declared as identical `NewTopic` beans
(3 partitions, RF 1) by **both** booking-service and notification-service —
creation is idempotent and whichever service starts first creates them
correctly; broker topic auto-creation is OFF (a pristine-broker consumer
subscription would otherwise auto-create 1-partition topics and strand keyed
messages outside its initial assignment). Topics:

| Topic | Payload |
|---|---|
| `seatsync.booking.confirmed` | `{"type":"BookingConfirmed","eventId*","bookingId","eventId","eventName","seatId","userId","userEmail","price","occurredAt"}` |
| `seatsync.hold.expired` | `{"type":"HoldExpired","eventId*","holdId","eventId","eventName","seatId","userId","userEmail","occurredAt"}` |
| `seatsync.waitlist.offered` | `{"type":"WaitlistOffered","eventId*","holdId","eventId","eventName","seatId","userId","userEmail","offerExpiresAt","occurredAt"}` |

`eventId*` = **`"messageId"` field**: unique UUID per event emission (the
outbox row id) used for consumer dedupe — named `messageId` to avoid clashing
with the domain `eventId` (the catalog event). Consumers MUST treat a
duplicate `messageId` as a no-op. `WaitlistOffered.holdId` is the
system-created offer hold (§6.3). Message key = domain eventId (ordering per
event). All events are written via the booking-service outbox (§6.3),
delivered at-least-once.

**Schema contracts:** every payload above has a JSON Schema (draft 2020-12)
in `contracts/events/*.schema.json` at the repo root — the wire contract.
Producer tests (booking-service) validate emitted payloads against these
schemas; consumer tests (notification-service) validate their fixtures.
CI (`contracts` job) rejects backward-incompatible schema changes on PRs
(removing a property, changing a property's type, adding to `required`);
adding optional properties is allowed. Schema evolution beyond that requires
a new topic version (e.g. `seatsync.booking.confirmed.v2`).

### 6.5 notification-service

`@KafkaListener` on all three topics, groupId `notification-service`.
For each message: **dedupe/claim first** — `notifications.message_id` (uuid,
UNIQUE, nullable for legacy rows) is the processed-message ledger. Claim by
inserting the row (status SENDING) before any send. On unique-constraint
conflict, load the existing row: status **SENT → skip** (duplicate delivery,
INFO log); status **FAILED → retry the send** on this delivery (update the
same row); status **SENDING → skip if fresher than 5 min** (concurrent
in-flight), otherwise treat as a crashed attempt and retry. Then compose and
send via `JavaMailSender` (SMTP env as before, from `noreply@seatsync.local`),
update the row to SENT|FAILED, and log (INFO, structured).

**Retry topics + DLT (non-blocking):** consumption failures are classified:
- **Transient** (mail transport errors, DB unavailability): the listener
  THROWS; spring-kafka's retry-topic machinery redelivers via delay-suffixed
  retry topics `<topic>-retry-1000` / `-2000` / `-4000` (one per backoff
  interval — spring-kafka's delay is a per-topic property, so exponential
  backoff requires one topic per interval; 4 total attempts) and finally
  parks the record in **`<topic>-dlt`** with the row left FAILED.
  Classification is an allowlist (`retryOn`): unknown exceptions go straight
  to the DLT rather than retry-storming.
- **Poison** (unparseable JSON, missing required fields): non-retryable —
  classified fatal, goes straight to the DLT, WARN log.
DLT records keep the original payload + exception headers. Replay procedure
documented in `docs/runbook-dlt-replay.md` — replay is safe at any time
because the message_id claim makes consumption idempotent.
`GET /api/notifications/recent` unchanged.
`GET /api/notifications/recent?limit=50` (ADMIN) → latest notifications.

### 6.6 ai-concierge-service

```
POST /api/concierge/chat  PUBLIC (rate-limited at gateway)
  body { "message": "…", "conversationId": "uuid?" }
  → 200 { "conversationId": "…", "answer": "…",
          "sources": [ {"title": "…", "snippet": "…", "score": 0.87} ],
          "confidence": "HIGH|MEDIUM|LOW",
          "escalatedToHuman": false }
POST /api/concierge/reindex   ADMIN → re-ingests the corpus
```

- Spring AI 1.1.8: `spring-ai-starter-model-openai` +
  `spring-ai-starter-vector-store-pgvector` + `spring-ai-advisors-vector-store`.
- Chat model `${OPENAI_CHAT_MODEL:gpt-4o-mini}`, embeddings
  `text-embedding-3-small`, key env `OPENAI_API_KEY`.
- **No API key configured → degrade, don't crash**: `/chat` returns 200 with
  `escalatedToHuman: true`, answer = "The AI concierge is offline right now.
  Please email support@seatsync.local." (check key at startup; guard bean).
- RAG: markdown corpus in `src/main/resources/corpus/*.md` (write 4–6 real
  docs: refund policy, hold/booking rules, accessibility, organizer guide,
  FAQ). Ingest at startup only if vector store is empty (TokenTextSplitter).
- Tools (`@Tool` methods) calling live services via `RestClient` + resilience4j
  (breaker `downstream`): `searchEvents(query, city, category)` → catalog
  `GET /api/catalog/events`; `getEventDetails(eventId)`; `getSeatAvailability(eventId)`
  → booking `GET /api/events/{id}/seats` summarized as counts. Base URLs env:
  `CATALOG_BASE_URL` (default http://localhost:8082), `BOOKING_BASE_URL`
  (default http://localhost:8083).
- System prompt MUST instruct: answer ONLY from provided context/tool results;
  if not grounded, say you're not sure and give `support@seatsync.local`
  (and set escalation). Confidence heuristic: HIGH if a tool was called or top
  RAG score ≥ 0.75; MEDIUM ≥ 0.55; else LOW ⇒ `escalatedToHuman: true`.
- Conversation memory: in-memory `MessageWindowChatMemory` keyed by conversationId.

### 6.7 api-gateway

Spring Cloud Gateway (`spring-cloud-starter-gateway-server-webflux`, reactive).
Routes (no path rewriting):

| Predicate | URI env (default) |
|---|---|
| `/api/auth/**` | `AUTH_URI` (http://localhost:8081) |
| `/api/catalog/**` | `CATALOG_URI` (http://localhost:8082) |
| `/api/holds/**`, `/api/bookings/**`, `/api/events/**`, `/ws/**` | `BOOKING_URI` (http://localhost:8083) |
| `/api/notifications/**` | `NOTIFICATION_URI` (http://localhost:8084) |
| `/api/concierge/**` | `CONCIERGE_URI` (http://localhost:8085) |

- Edge JWT: global filter validating Bearer token (jjwt) for all routes EXCEPT
  the public list in §4. Invalid/missing → 401 ProblemDetail JSON.
- Rate limiting: `RequestRateLimiter` with `RedisRateLimiter(20, 40)` per
  route (`/api/**` routes only, not `/ws`), `KeyResolver` = client IP =
  **remote address by default** (the gateway is the first hop; client-supplied
  `X-Forwarded-For` is spoofable and must not be trusted). Env
  `TRUST_XFF=true` (default false) switches to first-XFF for deployments
  behind a trusted load balancer.
- CORS (gateway only): allow origins `http://localhost:4200`, all methods,
  headers `*`, allow credentials, max age 3600. Downstream services must NOT
  add their own CORS config (avoids duplicate headers).

## 7. Docker

Each Java service has an identical multi-stage `Dockerfile`:

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE <port>
ENTRYPOINT ["java","-jar","app.jar"]
```

Compose service names = folder names; they are also the DNS hostnames
(e.g. `http://catalog-service:8082`).

## 8. Testing

- JUnit 5 + Mockito unit tests for core logic (booking confirm paths, hold
  rules, JWT filter, cache-aside).
- Testcontainers integration tests: `@SpringBootTest` + `@ServiceConnection`
  containers `postgres:16-alpine` (use `pgvector/pgvector:pg16` in ai service),
  `redis:7-alpine` (GenericContainer), `org.testcontainers:kafka` (KRaft,
  `apache/kafka-native:3.8.0` image via `org.testcontainers.kafka.KafkaContainer`).
- **booking-service MUST include** `ConcurrentBookingRaceIT`: seed 1 event
  with 10 AVAILABLE seats; fire 60 threads simultaneously (CountDownLatch
  start barrier) where each thread tries hold+confirm on a RANDOM seat of the
  10; assert exactly 10 bookings CONFIRMED, 10 inventory rows BOOKED, zero
  seats double-booked (each seat has ≤1 CONFIRMED booking), and every other
  request got a clean 409-style rejection. Plus a direct-optimistic-lock test:
  bypass holds, 60 threads → `confirmSeat` on the SAME seat → exactly 1 wins.
- Tests must not require external network beyond pulling images.

### 8.1 Adversarial & structural test standards (Step 2)

- **Chaos (booking-service)**: Toxiproxy (`org.testcontainers:toxiproxy`
  Boot-managed + `eu.rekawek.toxiproxy:toxiproxy-java:2.1.11`) between the
  app and Redis/Postgres/Kafka/catalog. The invariant asserted in EVERY
  scenario: `SELECT seat_id FROM bookings WHERE status='CONFIRMED' GROUP BY
  event_id, seat_id HAVING count(*)>1` is empty AND booked seats ≤ capacity.
  Availability MAY degrade (5xx/timeouts are acceptable and recorded);
  correctness may NOT. Results feed `docs/chaos-and-availability.md`.
- **Property-based (booking-service)**: jqwik (`net.jqwik:jqwik`, spring via
  `net.jqwik:jqwik-spring:0.12.0` or a manually-managed static context)
  over randomized hold/expire/confirm/cancel interleavings against real
  Postgres. Invariants: (1) no seat has 2 CONFIRMED bookings; (2) every
  CONFIRMED booking has a BookingConfirmed outbox row (same tx guarantee);
  (3) active (unexpired, HELD) holds per seat ≤ 1 and total active holds for
  an event ≤ remaining capacity.
- **Hold TTLs are configurable** (needed by the kill-node chaos run):
  `booking.hold-ttl` (default `PT5M`, env `HOLD_TTL`) and
  `booking.offer-ttl` (default `PT10M`, env `OFFER_TTL`). Redis PX and DB
  expires_at must both derive from these.
- **Pact (REST boundaries)**: pact-jvm **4.7.3** (`au.com.dius.pact.consumer:junit5`,
  `au.com.dius.pact.provider:junit5spring`). Pact files are COMMITTED to
  `contracts/pacts/<consumer>-<provider>.json` (consumer tests regenerate
  them; a stale/diverged pact fails the consumer build via file comparison
  or simply overwrites for review in the diff). Providers verify from that
  folder with `@PactFolder`. Boundaries: booking→catalog (event by id +
  seatmap), concierge→catalog (search + event by id), concierge→booking
  (live seats). Both sides run inside each service's normal `mvnw verify`,
  so CI verifies both sides. Provider states seed via test profile.
- **ArchUnit** (`com.tngtech.archunit:archunit-junit5:1.4.2`, every Java
  service with controllers): (1) no class in `..api..`/`..web..` may access
  `..repo..`/`..repository..` classes; (2) no class in `..api..`/`..web..`
  may depend on `@Entity` classes (DTOs only at the edge); (3) no
  `@Transactional` on private methods (Spring silently ignores them);
  (4) no service may import another SeatSync service's packages
  (`com.seatsync.<other>..`). Violations are fixed by refactoring, not by
  weakening the rule.
- **PIT mutation testing (booking-service ONLY, the invariant code)**:
  `org.pitest:pitest-maven:1.25.7` + `pitest-junit5-plugin:1.2.3` in a
  Maven profile `pit`; targetClasses `com.seatsync.booking.service.*`,
  targetTests unit tests only (`*Test`, never `*IT`). `mutationThreshold`
  set to the honestly-measured score rounded DOWN (never tuned up by
  excluding surviving mutants without a comment explaining each exclusion).
- **JaCoCo**: every Java service; merged unit+IT coverage (prepare-agent +
  prepare-agent-integration + merge + report/check at `verify`); the `check`
  minimum is the honestly-measured line coverage rounded down to the nearest
  5% (a ratchet, not a target). Real numbers are stated in the README.

## 9. Frontend contract notes

- Angular dev server proxies `/api` and `/ws` → `http://localhost:8080`.
- Nginx container does the same (`proxy_pass http://api-gateway:8080`,
  WS upgrade headers for `/ws`).
- Auth: store tokens in memory + localStorage, attach
  `Authorization: Bearer` via interceptor, auto-refresh on 401 once.
- Seat map colors: AVAILABLE (primary/outlined), HELD-by-you (accent, with
  countdown), HELD-by-others (greyed, disabled), BOOKED (dark/disabled).
- STOMP over SockJS: `sockjs-client` + `@stomp/stompjs` (or `@stomp/rx-stomp`),
  connect to `/ws`, subscribe `/topic/events/{id}/seats`.
