package com.seatsync.booking;

import com.seatsync.booking.catalog.CatalogGateway;
import com.seatsync.booking.support.AbstractChaosIT;
import com.seatsync.booking.support.ChaosInfra;
import com.seatsync.booking.support.TestTokens;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chaos scenario d (conventions 8.1) — POSTGRES POOL EXHAUSTED. This context
 * runs with a deliberately tiny Hikari pool (3 connections, 4s acquisition
 * timeout) while a 2000ms latency toxic sits on the Postgres proxy; 30
 * concurrent hold+confirm requests then fight over the pool. Many requests are
 * EXPECTED to shed with clean 5xx ProblemDetails (that is the availability
 * degradation, and the counts are recorded in the verdict); the invariant must
 * hold both right after the storm and after the toxic is removed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.connection-timeout=4000",
        "spring.data.redis.timeout=2000ms",
        "spring.data.redis.connect-timeout=2000ms",
        "management.tracing.sampling.probability=0.0",
        "management.zipkin.tracing.export.enabled=false"
})
class ChaosPoolExhaustionIT extends AbstractChaosIT {

    private static final int THREADS = 30;
    private static final int CONTESTED_SEATS = 10;
    /** One extra seat proves recovery once the toxic is gone. */
    private static final int CAPACITY = CONTESTED_SEATS + 1;

    @DynamicPropertySource
    static void chaosProperties(DynamicPropertyRegistry registry) {
        ChaosInfra.createDatabase("chaos_pool");
        registry.add("spring.datasource.url", () -> ChaosInfra.proxiedJdbcUrl("chaos_pool"));
        registry.add("spring.datasource.username", ChaosInfra.POSTGRES::getUsername);
        registry.add("spring.datasource.password", ChaosInfra.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", ChaosInfra::redisProxyHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(ChaosInfra.redisProxyPort()));
        registry.add("spring.kafka.bootstrap-servers", ChaosInfra::kafkaProxyBootstrap);
    }

    /** Catalog stays faked in this context (inventory is seeded via SQL). */
    @MockitoBean
    private CatalogGateway catalogGateway;

    @AfterEach
    void healProxies() {
        ChaosInfra.healEverything();
    }

    @Test
    void poolExhaustionUnderDbLatencyShedsLoadCleanlyButNeverOversells() throws Exception {
        UUID eventId = UUID.randomUUID();
        seedSeats(eventId, CAPACITY, new BigDecimal("42.00"));

        ChaosInfra.POSTGRES_PROXY.toxics()
                .latency("pg-latency", ToxicDirection.DOWNSTREAM, 2000);

        Queue<Integer> holdStatuses = new ConcurrentLinkedQueue<>();
        Queue<Integer> confirmStatuses = new ConcurrentLinkedQueue<>();
        Queue<Throwable> unexpectedErrors = new ConcurrentLinkedQueue<>();
        try {
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(THREADS);
            for (int i = 0; i < THREADS; i++) {
                final String seatId = seat((i % CONTESTED_SEATS) + 1);
                final String token = TestTokens.attendee(UUID.randomUUID(), "pool-" + i + "@test.io");
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
            assertThat(done.await(240, TimeUnit.SECONDS))
                    .as("every request must complete (time out or finish), never hang")
                    .isTrue();
            pool.shutdownNow();
        } finally {
            ChaosInfra.POSTGRES_PROXY.toxics().get("pg-latency").remove();
        }

        assertThat(unexpectedErrors).isEmpty();
        assertThat(holdStatuses).hasSize(THREADS);
        // Clean outcomes only: 201/200 winners, 409 seat conflicts, 5xx shed load.
        assertThat(holdStatuses).allMatch(s -> s == 200 || s == 201 || s == 409 || (s >= 500 && s < 600));
        assertThat(confirmStatuses).allMatch(s -> s == 200 || s == 201 || s == 409 || (s >= 500 && s < 600));

        Map<Integer, Long> holdCounts = countByStatus(holdStatuses);
        Map<Integer, Long> confirmCounts = countByStatus(confirmStatuses);
        long shed = holdStatuses.stream().filter(s -> s >= 500).count()
                + confirmStatuses.stream().filter(s -> s >= 500).count();

        // Invariant right after the storm, with the toxic already removed.
        assertNoOversell(eventId, CAPACITY);

        // Recovery: with the toxic gone, an untouched seat books normally.
        String token = TestTokens.attendee(UUID.randomUUID(), "pool-recovery@test.io");
        ResponseEntity<Map<String, Object>> hold = postJson("/api/holds",
                Map.of("eventId", eventId.toString(), "seatId", seat(CAPACITY)), token);
        assertThat(hold.getStatusCode().value()).isEqualTo(201);
        ResponseEntity<Map<String, Object>> confirm = postJson("/api/bookings",
                Map.of("holdId", String.valueOf(hold.getBody().get("holdId"))), token);
        assertThat(confirm.getStatusCode().value()).isEqualTo(201);

        // And the invariant STILL holds after recovery traffic.
        assertNoOversell(eventId, CAPACITY);
        verdict("POSTGRES_POOL_EXHAUSTED",
                THREADS + " concurrent buyers vs pool=3 + 2000ms DB latency: hold statuses "
                        + holdCounts + ", confirm statuses " + confirmCounts + " (" + shed
                        + " requests shed with clean 5xx, zero hangs); normal booking resumed "
                        + "immediately after the toxic was removed");
    }

    private static Map<Integer, Long> countByStatus(Queue<Integer> statuses) {
        Map<Integer, Long> counts = new TreeMap<>();
        for (Integer status : statuses) {
            counts.merge(status, 1L, Long::sum);
        }
        return counts;
    }
}
