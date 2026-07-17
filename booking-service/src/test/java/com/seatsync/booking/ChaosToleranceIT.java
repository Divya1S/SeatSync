package com.seatsync.booking;

import com.seatsync.booking.catalog.CatalogGateway;
import com.seatsync.booking.support.AbstractChaosIT;
import com.seatsync.booking.support.ChaosInfra;
import com.seatsync.booking.support.TestTokens;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Chaos scenarios a-c (conventions 8.1): Redis cut mid-booking, a Redis latency
 * spike, and a Kafka outage — all injected through Toxiproxy while real HTTP
 * requests run against the full stack. In every scenario availability may
 * degrade (recorded in the CHAOS-VERDICT line) but the no-oversell invariant
 * must hold.
 *
 * <p>Degradation profile of this context (noted per 8.1): the Lettuce command
 * timeout is capped at 4500ms ({@code spring.data.redis.timeout}) — the 60s
 * default would park requests on a dead Redis for a minute; 4500ms keeps every
 * request bounded while still exceeding the 3000ms latency toxic so slow-Redis
 * requests complete rather than time out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.data.redis.timeout=4500ms",
        "spring.data.redis.connect-timeout=2000ms",
        "spring.datasource.hikari.maximum-pool-size=15",
        "management.tracing.sampling.probability=0.0",
        "management.zipkin.tracing.export.enabled=false"
})
class ChaosToleranceIT extends AbstractChaosIT {

    @DynamicPropertySource
    static void chaosProperties(DynamicPropertyRegistry registry) {
        ChaosInfra.createDatabase("chaos_main");
        registry.add("spring.datasource.url", () -> ChaosInfra.proxiedJdbcUrl("chaos_main"));
        registry.add("spring.datasource.username", ChaosInfra.POSTGRES::getUsername);
        registry.add("spring.datasource.password", ChaosInfra.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", ChaosInfra::redisProxyHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(ChaosInfra.redisProxyPort()));
        registry.add("spring.kafka.bootstrap-servers", ChaosInfra::kafkaProxyBootstrap);
    }

    /** Catalog stays faked in this context (inventory is seeded via SQL). */
    @MockitoBean
    private CatalogGateway catalogGateway;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void healProxies() {
        ChaosInfra.healEverything();
        // Do not leak a broken Redis link into the next scenario: wait until the
        // app's Lettuce connection has actually recovered.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .untilAsserted(() -> redisTemplate.opsForValue()
                        .set("chaos:heal-probe", "ok", Duration.ofMinutes(1)));
    }

    /**
     * Scenario a — REDIS DOWN MID-BOOKING. Holds are taken while Redis is up,
     * then the proxy is cut entirely. Confirms must STILL succeed: the DB is
     * authoritative and the Redis delete is a best-effort post-commit adjunct.
     * New holds during the outage must fail with a bounded, clean 5xx (the hold
     * path cannot claim atomically without Redis). After the proxy is restored
     * the system recovers without intervention.
     */
    @Test
    void redisDownMidBookingConfirmsSucceedNewHoldsFailCleanlyThenRecover() throws Exception {
        UUID eventId = UUID.randomUUID();
        int capacity = 8;
        seedSeats(eventId, capacity, new BigDecimal("49.00"));

        // 6 valid holds while Redis is healthy.
        record Holder(String token, String holdId) {
        }
        List<Holder> holders = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            String token = TestTokens.attendee(UUID.randomUUID(), "redis-down-" + i + "@test.io");
            ResponseEntity<Map<String, Object>> hold = postJson("/api/holds",
                    Map.of("eventId", eventId.toString(), "seatId", seat(i)), token);
            assertThat(hold.getStatusCode().value()).isEqualTo(201);
            holders.add(new Holder(token, String.valueOf(hold.getBody().get("holdId"))));
        }

        ChaosInfra.REDIS_PROXY.disable();

        Queue<Integer> confirmStatuses = new ConcurrentLinkedQueue<>();
        Queue<Throwable> unexpectedErrors = new ConcurrentLinkedQueue<>();
        long outageHoldElapsedMs;
        int outageHoldStatus;
        try {
            // Concurrent confirms with Redis fully dark.
            ExecutorService pool = Executors.newFixedThreadPool(holders.size());
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(holders.size());
            for (Holder holder : holders) {
                pool.submit(() -> {
                    try {
                        start.await(30, TimeUnit.SECONDS);
                        ResponseEntity<Map<String, Object>> confirm = postJson("/api/bookings",
                                Map.of("holdId", holder.holdId()), holder.token());
                        confirmStatuses.add(confirm.getStatusCode().value());
                    } catch (Throwable t) {
                        unexpectedErrors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(90, TimeUnit.SECONDS))
                    .as("confirms must complete during the Redis outage, not hang").isTrue();
            pool.shutdownNow();

            assertThat(unexpectedErrors).isEmpty();
            assertThat(confirmStatuses).hasSize(6);
            assertThat(confirmStatuses)
                    .as("DB is authoritative: every valid hold confirms although Redis is dark")
                    .allMatch(s -> s == 201);

            // A NEW hold during the outage: clean, bounded failure — never a hang.
            String outageToken = TestTokens.attendee(UUID.randomUUID(), "redis-down-outage@test.io");
            long t0 = System.nanoTime();
            ResponseEntity<Map<String, Object>> outageHold = postJson("/api/holds",
                    Map.of("eventId", eventId.toString(), "seatId", seat(7)), outageToken);
            outageHoldElapsedMs = (System.nanoTime() - t0) / 1_000_000;
            outageHoldStatus = outageHold.getStatusCode().value();
            assertThat(outageHoldStatus)
                    .as("hold without Redis must be rejected with a clean 4xx/5xx ProblemDetail")
                    .isGreaterThanOrEqualTo(400).isLessThan(600);
            assertThat(outageHold.getBody()).containsKey("status"); // RFC 7807 body, not a stack trace
            assertThat(outageHoldElapsedMs)
                    .as("failure must be bounded by the Lettuce command timeout, not a hang")
                    .isLessThan(15_000);
        } finally {
            ChaosInfra.REDIS_PROXY.enable();
        }

        // Recovery: once the proxy is back, a brand-new hold succeeds (Lettuce reconnects).
        String recoveryToken = TestTokens.attendee(UUID.randomUUID(), "redis-down-recovery@test.io");
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    ResponseEntity<Map<String, Object>> recovered = postJson("/api/holds",
                            Map.of("eventId", eventId.toString(), "seatId", seat(8)), recoveryToken);
                    assertThat(recovered.getStatusCode().value()).isIn(200, 201);
                });

        assertNoOversell(eventId, capacity);
        verdict("REDIS_DOWN_MID_BOOKING",
                "6/6 in-flight confirms succeeded during the outage (DB authoritative); "
                        + "new holds failed fast+clean (HTTP " + outageHoldStatus + " in "
                        + outageHoldElapsedMs + "ms, no hang); holds recovered without restart "
                        + "after the proxy was restored");
    }

    /**
     * Scenario b — REDIS 3s LATENCY SPIKE. A hold+confirm storm on 4 seats with
     * every Redis command delayed by 3000ms. All requests must complete
     * (bounded by the 4500ms Lettuce command timeout in this profile), exactly
     * one hold per seat wins, and nothing oversells.
     */
    @Test
    void redisLatencySpikeStormStaysBoundedAndNeverOversells() throws Exception {
        UUID eventId = UUID.randomUUID();
        int capacity = 4;
        int threads = 12;
        seedSeats(eventId, capacity, new BigDecimal("35.00"));

        ChaosInfra.REDIS_PROXY.toxics()
                .latency("redis-latency", ToxicDirection.DOWNSTREAM, 3000);

        Queue<Integer> holdStatuses = new ConcurrentLinkedQueue<>();
        Queue<Integer> confirmStatuses = new ConcurrentLinkedQueue<>();
        Queue<Throwable> unexpectedErrors = new ConcurrentLinkedQueue<>();
        try {
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                final String seatId = seat((i % capacity) + 1);
                final String token = TestTokens.attendee(UUID.randomUUID(), "latency-" + i + "@test.io");
                pool.submit(() -> {
                    try {
                        start.await(30, TimeUnit.SECONDS);
                        ResponseEntity<Map<String, Object>> hold = postJson("/api/holds",
                                Map.of("eventId", eventId.toString(), "seatId", seatId), token);
                        holdStatuses.add(hold.getStatusCode().value());
                        if (hold.getStatusCode().value() == 201) {
                            ResponseEntity<Map<String, Object>> confirm = postJson("/api/bookings",
                                    Map.of("holdId", String.valueOf(hold.getBody().get("holdId"))), token);
                            confirmStatuses.add(confirm.getStatusCode().value());
                        }
                    } catch (Throwable t) {
                        unexpectedErrors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(120, TimeUnit.SECONDS))
                    .as("every request must complete under the latency spike (bounded, no hang)")
                    .isTrue();
            pool.shutdownNow();
        } finally {
            ChaosInfra.REDIS_PROXY.toxics().get("redis-latency").remove();
        }

        assertThat(unexpectedErrors).isEmpty();
        assertThat(holdStatuses).hasSize(threads);
        assertThat(holdStatuses)
                .as("slow Redis may slow holds down but never turns them into 5xx")
                .allMatch(s -> s == 201 || s == 409);
        assertThat(holdStatuses.stream().filter(s -> s == 201).count())
                .as("exactly one hold per seat wins under 3s Redis latency")
                .isEqualTo(capacity);
        assertThat(confirmStatuses).hasSize(capacity);
        assertThat(confirmStatuses).allMatch(s -> s == 201);

        assertNoOversell(eventId, capacity);
        verdict("REDIS_LATENCY_3S",
                "all " + threads + " requests completed (bounded by the 4500ms Lettuce command "
                        + "timeout set in the test profile; default 60s would stall); " + capacity
                        + " holds+confirms succeeded slowly, rivals got clean 409s, zero oversell");
    }

    /**
     * Scenario c — KAFKA DOWN. Bookings must return 201 fast (the request path
     * never touches Kafka), events are PARKED in the transactional outbox and
     * retried, and after the broker returns EVERY row is delivered — nothing
     * lost. The 60s outage window is compressed to the ~10-15s it takes to
     * observe parked rows with retry attempts; the semantics are identical.
     */
    @Test
    void kafkaOutageParksEventsInOutboxThenDeliversAllAfterRestore() throws Exception {
        UUID eventId = UUID.randomUUID();
        int capacity = 3;
        seedSeats(eventId, capacity, new BigDecimal("60.00"));

        ChaosInfra.KAFKA_PROXY.disable();
        long outageStart = System.nanoTime();
        long slowestConfirmMs = 0;
        try {
            for (int i = 1; i <= capacity; i++) {
                String token = TestTokens.attendee(UUID.randomUUID(), "kafka-down-" + i + "@test.io");
                ResponseEntity<Map<String, Object>> hold = postJson("/api/holds",
                        Map.of("eventId", eventId.toString(), "seatId", seat(i)), token);
                assertThat(hold.getStatusCode().value()).isEqualTo(201);

                long t0 = System.nanoTime();
                ResponseEntity<Map<String, Object>> confirm = postJson("/api/bookings",
                        Map.of("holdId", String.valueOf(hold.getBody().get("holdId"))), token);
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                slowestConfirmMs = Math.max(slowestConfirmMs, elapsedMs);
                assertThat(confirm.getStatusCode().value())
                        .as("booking must succeed while Kafka is dark").isEqualTo(201);
                assertThat(elapsedMs)
                        .as("the request path never waits on Kafka").isLessThan(5_000);
            }

            // Parked, not lost: rows stay unpublished AND the relay provably keeps
            // retrying them across MULTIPLE cycles (attempts >= 2 stretches the
            // observed outage to the compressed 10-15s window; same semantics as 60s).
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
                    .untilAsserted(() -> {
                        List<Map<String, Object>> rows = jdbc.queryForList(
                                "select published_at, attempts from outbox_events where message_key = ?",
                                eventId.toString());
                        assertThat(rows).hasSize(capacity);
                        assertThat(rows).allSatisfy(row -> {
                            assertThat(row.get("published_at"))
                                    .as("events must stay parked while the broker is unreachable")
                                    .isNull();
                            assertThat(((Number) row.get("attempts")).intValue())
                                    .as("the relay must keep retrying parked rows across cycles")
                                    .isGreaterThanOrEqualTo(2);
                        });
                    });
        } finally {
            ChaosInfra.KAFKA_PROXY.enable();
        }
        long outageSeconds = (System.nanoTime() - outageStart) / 1_000_000_000;

        // Nothing lost: after the broker returns, the relay drains every parked row.
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(2))
                .untilAsserted(() -> {
                    Integer unpublished = jdbc.queryForObject(
                            "select count(*) from outbox_events where message_key = ? "
                                    + "and published_at is null",
                            Integer.class, eventId.toString());
                    assertThat(unpublished)
                            .as("every parked event must be delivered once Kafka returns")
                            .isZero();
                });
        Integer delivered = jdbc.queryForObject(
                "select count(*) from outbox_events where message_key = ? and published_at is not null",
                Integer.class, eventId.toString());
        assertThat(delivered).isEqualTo(capacity);

        assertNoOversell(eventId, capacity);
        verdict("KAFKA_DOWN_" + outageSeconds + "S",
                capacity + "/" + capacity + " bookings returned 201 fast (slowest confirm "
                        + slowestConfirmMs + "ms) during a ~" + outageSeconds + "s broker outage; "
                        + "events parked in the outbox with retries, all " + capacity
                        + " delivered after restore — nothing lost");
    }
}
