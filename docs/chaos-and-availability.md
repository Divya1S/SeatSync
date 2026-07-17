# Chaos & Availability — what degrades, what never does

The system's contract under failure: **availability may degrade; correctness
may not.** "Correctness" here is one invariant — a seat is never sold twice
and sales never exceed capacity — plus its supporting guarantees (holds
always expire; committed events are always eventually delivered, exactly-once
in effect).

Two harnesses enforce this:

1. **Toxiproxy chaos suite** (`booking-service` `ChaosToleranceIT`,
   runs in CI): injects the failures below between booking-service and its
   dependencies, then asserts
   `SELECT … FROM bookings WHERE status='CONFIRMED' GROUP BY event_id,
   seat_id HAVING count(*)>1` returns nothing and booked ≤ capacity.
2. **Kill-the-node test** (`infra/chaos/kill_node_test.py`, manual — needs
   the live compose stack): SIGKILLs one of two booking replicas
   mid-transaction under load.

## Failure matrix (measured, not hypothesized)

| Failure injected | Availability impact (measured, tolerated) | Correctness (asserted) |
|---|---|---|
| Redis fully down mid-booking | In-flight confirms: 6/6 succeeded during the outage (DB is authoritative). New holds failed fast and clean (5xx in ~12–18 ms, bounded by a 4.5 s Lettuce command timeout — no hangs). Holds recovered without restart when Redis returned | **HELD** — zero oversell |
| Redis +3 s latency on every call | All 12 concurrent requests completed within the command timeout; 4 hold+confirm pairs succeeded slowly, rivals got clean 409s | **HELD** — zero oversell |
| Kafka broker down (~16 s window, semantics of 60 s preserved via multi-cycle relay retries) | 3/3 bookings returned 201 fast (slowest confirm 12 ms). Events parked in the outbox (attempts ≥ 2 observed), all delivered after restore — **nothing lost** | **HELD** |
| Postgres pool exhausted (pool=3, +2 s DB latency, 30 concurrent buyers) | Load shed: 2× 201, 28× clean 5xx, zero hangs; normal booking resumed immediately after the toxic was removed | **HELD** — zero oversell |
| Network partition booking ↔ catalog | Unseeded events degraded to clean 503 ProblemDetail (real Feign path). Already-seeded events booked normally throughout the partition | **HELD** |
| SIGKILL booking replica under load (measured 2026-07-16: 12 users storming a 66-seat event, kill at t+6 s of 30 s) | 36 failed requests during failover; the gateway's connection pool stayed pinned to the dead IP for ~1–2 min before evicting (self-healed; future work: gateway retry-on-connect-failure filter). Survivor kept serving throughout | **HELD** — 46 bookings, 0 double-booked seats, orphaned holds swept by the survivor within TTL+sweep, outbox fully drained |

## Why correctness survives each case (design recap)

- **Redis loss** can only remove the *fast-path* seat claim. The confirm
  transaction re-verifies the hold from Postgres and the `@Version`
  optimistic lock admits exactly one `AVAILABLE→BOOKED` transition — Redis
  is never consulted for the final decision.
- **Kafka loss** never touches the request path: events commit to the outbox
  in the booking transaction; the relay delivers when the broker returns
  (at-least-once + consumer dedupe = exactly-once effect).
- **Postgres degradation** degrades everything — by design. When the source
  of truth is unavailable the correct behavior is to fail requests, not to
  guess. Timeouts/5xx are the accepted cost; no state is written outside
  Postgres transactions, so nothing can diverge.
- **Catalog partition** only blocks *first-touch inventory seeding* (503
  with a clear message). Already-seeded events sell normally — booking holds
  its own copy of seat inventory precisely so catalog is not a runtime
  dependency of the sale.
- **Replica death**: holds orphaned by the dead node expire via DB
  timestamps (the survivor's sweeper claims them with `SKIP LOCKED`);
  outbox rows claimed by the dead node unlock when its connections die and
  the survivor's relay publishes them. Nothing about the invariant lives in
  process memory.

## Running the kill-node test

```bash
docker compose -f docker-compose.yml -f infra/chaos/docker-compose.chaos.yml \
  up -d --build --scale booking-service=2
# wait for health, then:
python3 infra/chaos/kill_node_test.py
# restore the normal topology afterwards:
docker compose up -d --remove-orphans
```
