# Runbook — Dead-letter topic (DLT) inspection & replay

Applies to notification-service. Consumption failures are retried
non-blockingly via delay-suffixed retry topics (`<topic>-retry-1000`,
`-retry-2000`, `-retry-4000` — 4 total attempts, exponential backoff; one
topic per interval because Kafka delay is a per-topic property) and then
parked in **`<topic>-dlt`** — one DLT per main topic:

```
seatsync.booking.confirmed-dlt
seatsync.hold.expired-dlt
seatsync.waitlist.offered-dlt
```

Poison messages (unparseable JSON / missing required fields) skip the retries
and go straight to the DLT. The original payload is preserved; the failure
cause travels in Kafka headers (`kafka_dlt-exception-message`,
`kafka_dlt-original-topic`, `kafka_dlt-original-offset`).

## 1. Inspect

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic seatsync.booking.confirmed-dlt \
  --from-beginning --timeout-ms 5000 \
  --property print.headers=true --property print.offset=true
```

Correlate with the service's own ledger (row status FAILED):

```bash
docker compose exec postgres psql -U seatsync -d seatsync_notification \
  -c "SELECT message_id, type, recipient, status, created_at
      FROM notifications WHERE status = 'FAILED' ORDER BY created_at DESC LIMIT 20;"
```

## 2. Fix the cause

Typical causes: Mailpit/SMTP outage (transient — fix connectivity), a
malformed producer payload (poison — fix the producer; the schema CI gate
should have caught it), DB outage during consumption.

## 3. Replay

Replay is **safe at any time and any number of times**: consumption is
idempotent on `messageId` (a SENT claim is never re-sent; a FAILED claim is
retried and updated in place). Pipe the DLT back onto the main topic:

```bash
docker compose exec kafka bash -c '
  /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic seatsync.booking.confirmed-dlt \
    --from-beginning --timeout-ms 10000 \
  | /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 \
    --topic seatsync.booking.confirmed'
```

Notes:
- Replaying a **poison** message will land it back in the DLT (it is still
  unparseable). Fix or discard it; discarding = simply not replaying —
  the DLT retains it until topic retention expires.
- The console pipe drops message keys (acceptable here — keys only order
  per-event and replay volume is tiny). For key-preserving bulk replay use
  `kcat -K` or a small script.
- After a replay, verify: FAILED rows flip to SENT and the emails appear in
  Mailpit (http://localhost:8025).

## 4. Monitor

DLT depth is the alarm signal — in production, alert on consumer-group lag
of a dedicated `dlt-monitor` group or on `kafka_topic_partition_current_offset`
for `*-dlt` topics. Locally: the inspect command above with `--timeout-ms`.
