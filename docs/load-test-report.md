# SeatSync — Load Test Report (k6)

**Date:** 2026-07-16 · **Environment:** single Apple Silicon laptop, the full
Docker Compose stack (6 services + Postgres/Redis/Kafka), k6 in a container on
the compose network. All traffic goes **through the API gateway** — every
request pays the gateway hop, edge-JWT filter, and Redis rate limiter (raised
to 2000 req/s for these runs via `RATE_LIMIT_REPLENISH`, so we measure the
services, not the limiter). Numbers demonstrate *relative* behaviour of the
cache/hold layers on laptop-class hardware, not production capacity.

## 1. Read path — catalog browse (`infra/k6/browse-load.js`)

Iteration = event search + event detail + seat map. Two datasets:
the small seeded events, and a deliberately heavy **7,200-seat event**
("Mega Arena", 393 KB seat-map response) created via the organizer API.
Cache toggled with `CATALOG_CACHE_ENABLED` (compose env → catalog-service).

| Scenario | Throughput | detail p95 | seat map p50 | seat map p95 | Errors |
|---|---|---|---|---|---|
| Small events, cache **on**, 30 VUs | 148 req/s | 4.2 ms | 1.1 ms | 3.9 ms | 0 |
| Small events, cache **off**, 30 VUs | 148 req/s | 3.3 ms | 1.2 ms | 2.9 ms | 0 |
| Mega Arena, cache **off**, 100 VUs | 494 req/s | 1.8 ms | 1.8 ms | 3.2 ms | 0 |
| Mega Arena, cache **on**, 100 VUs | 491 req/s | 2.4 ms | 3.0 ms | 5.7 ms | 0 |
| Mega Arena, cache **off**, 300 VUs | **1,473 req/s** | — | 1.9 ms | 5.7 ms | 0 |
| Mega Arena, cache **on**, 300 VUs | 1,462 req/s | — | 3.5 ms | 14.7 ms | 0 |

### The honest finding

At this scale the cache-aside layer **does not lower read latency — it
slightly raises it** on the largest responses. Why:

- The uncached read is genuinely cheap: the seat map is *derived* from ~30
  section rows (never per-seat rows), so "no cache" = a tiny indexed query on
  a warm local Postgres + in-memory expansion (~2 ms even for 7,200 seats).
- The cached read moves a 393 KB JSON blob across the Redis connection and
  re-deserializes it before responding — for big values, that transfer *is*
  the latency.

This is the textbook lesson about cache-aside: **it pays when the underlying
read is expensive** (contended connection pools, complex joins, cold pages, a
network-distant or replica-lagged database, thundering-herd reads during
on-sale spikes) — none of which a warm, idle, localhost Postgres exhibits. The
layer is kept (and toggleable) because its purpose here is *headroom and
isolation*, not laptop latency: with the cache on, a hot event's reads put
zero load on the database that is simultaneously processing booking writes —
Hikari's 10-connection pool never competes with the on-sale traffic. Two
follow-ups would make it strictly better and are noted as future work: store
the serialized response bytes and serve them without re-parsing, and only
cache seat maps below a size threshold (big values belong behind a CDN/ETag,
not in Redis).

## 2. Write path — booking storm (`infra/k6/booking-storm.js`)

50 users (each registered via the API in setup) × 3 iterations race to
hold + immediately confirm seats of one **44-seat** workshop event, through
the gateway. This is the Testcontainers race test, replayed over real HTTP
against the live stack:

| Metric | Value |
|---|---|
| Purchase attempts (hold→confirm sequences) | 150 |
| **Bookings confirmed** | **44 — exactly the seat count, event 100% sold out** |
| Clean seat conflicts (409) | 74 |
| **Server errors (5xx)** | **0** |
| **Oversold seats** | **0** |
| Request latency p50 / p90 / p95 | 67 ms / 204 ms / 632 ms |

The p95 tail is the *inventory-seeding stampede*: the very first holds trigger
a one-time Feign fetch of the seat map from catalog plus 44 inserts, guarded
by a Redisson lock that 50 concurrent VUs promptly pile up behind. After
seeding, hold+confirm settles around the median (~67 ms for the full
two-request sequence). Losers fail in milliseconds — the Redis `SET NX` gate
rejects them before any database transaction starts.

Also verified live, separately: with **Kafka and notification-service
stopped**, hold + confirm still returned `201 CONFIRMED` in 70 ms — publishing
is fire-and-forget behind a circuit breaker, so the messaging tier can never
block a sale.

## 3. Why the hold layer matters even though the optimistic lock is the guard

Without holds, all 150 contenders would open booking transactions and 106
would lose at commit time inside Postgres (version conflicts) — wasted
round-trips and row contention exactly when the system is busiest. With the
Redis `SET NX` hold as the first gate, at most one contender per seat ever
reaches the database; the `@Version` optimistic lock runs essentially
uncontended and exists for *correctness* (Redis failover/flush scenarios),
not throughput. The 74 rejections above cost a sub-millisecond cache op each.

## 4. Reproducing

```bash
docker compose up -d --build

# read path (heavy event): create it once via the organizer API, then
RATE_LIMIT_REPLENISH=2000 RATE_LIMIT_BURST=4000 docker compose up -d api-gateway
CATALOG_CACHE_ENABLED=false docker compose up -d catalog-service   # baseline
docker run --rm -i --network seatsync_default -e BASE_URL=http://api-gateway:8080 \
  -e VUS=100 -e QUERY=Mega grafana/k6 run - < infra/k6/browse-load.js
docker compose up -d catalog-service                               # cache back on
docker run --rm -i --network seatsync_default -e BASE_URL=http://api-gateway:8080 \
  -e VUS=100 -e QUERY=Mega grafana/k6 run - < infra/k6/browse-load.js

# write path
docker run --rm -i --network seatsync_default -e BASE_URL=http://api-gateway:8080 \
  -e EVENT_ID=<uuid-of-a-small-event> grafana/k6 run - < infra/k6/booking-storm.js

docker compose up -d api-gateway   # restore default rate limits
```
