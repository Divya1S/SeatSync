package com.seatsync.booking;

import com.seatsync.booking.support.AbstractChaosIT;
import com.seatsync.booking.support.ChaosInfra;
import com.seatsync.booking.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chaos scenario e (conventions 8.1) — network PARTITION between booking and
 * catalog. Unlike every other IT, this context wires the REAL CatalogGateway
 * (OpenFeign + resilience4j, no MockitoBean): {@code catalog.base-url} points
 * at a blackholed Toxiproxy listener, so all catalog calls fail at the socket.
 *
 * <p>Expected split-brain behavior: an event whose inventory was never seeded
 * cannot be booked (503 ProblemDetail "Event catalog temporarily unavailable"),
 * while events with seeded inventory keep booking normally throughout the
 * partition — the catalog is only needed for first-touch seeding.
 *
 * <p>Postgres/Redis/Kafka connect directly (unproxied) here: only the
 * booking-to-catalog link is partitioned.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.tracing.sampling.probability=0.0",
        "management.zipkin.tracing.export.enabled=false"
})
class ChaosCatalogPartitionIT extends AbstractChaosIT {

    @DynamicPropertySource
    static void chaosProperties(DynamicPropertyRegistry registry) {
        ChaosInfra.createDatabase("chaos_catalog");
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + ChaosInfra.POSTGRES.getHost()
                + ":" + ChaosInfra.POSTGRES.getMappedPort(5432) + "/chaos_catalog");
        registry.add("spring.datasource.username", ChaosInfra.POSTGRES::getUsername);
        registry.add("spring.datasource.password", ChaosInfra.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", ChaosInfra.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> ChaosInfra.REDIS.getMappedPort(6379).toString());
        registry.add("spring.kafka.bootstrap-servers", ChaosInfra.KAFKA::getBootstrapServers);
        // The partition itself: the real Feign client dials a dead listener.
        registry.add("catalog.base-url", ChaosInfra::blackholedCatalogUrl);
    }

    @Test
    void partitionOnlyBlocksUnseededEventsSeededEventsBookNormally() {
        UUID seededEvent = UUID.randomUUID();
        UUID unseededEvent = UUID.randomUUID();
        int capacity = 2;
        seedSeats(seededEvent, capacity, new BigDecimal("25.00"));

        String tokenA = TestTokens.attendee(UUID.randomUUID(), "partition-a@test.io");
        String tokenB = TestTokens.attendee(UUID.randomUUID(), "partition-b@test.io");

        // Unseeded event: catalog is required for first-touch seeding -> clean 503.
        ResponseEntity<Map<String, Object>> unseededHold = postJson("/api/holds",
                Map.of("eventId", unseededEvent.toString(), "seatId", seat(1)), tokenA);
        assertThat(unseededHold.getStatusCode().value())
                .as("hold on an unseeded event must degrade to 503 during the partition")
                .isEqualTo(503);
        assertThat(String.valueOf(unseededHold.getBody().get("detail")))
                .contains("Event catalog temporarily unavailable");
        assertThat(unseededHold.getBody().get("status")).isEqualTo(503); // RFC 7807 ProblemDetail

        // Seeded event: full hold+confirm cycle works mid-partition.
        ResponseEntity<Map<String, Object>> hold = postJson("/api/holds",
                Map.of("eventId", seededEvent.toString(), "seatId", seat(1)), tokenA);
        assertThat(hold.getStatusCode().value())
                .as("seeded events must keep booking during the partition").isEqualTo(201);
        ResponseEntity<Map<String, Object>> confirm = postJson("/api/bookings",
                Map.of("holdId", String.valueOf(hold.getBody().get("holdId"))), tokenA);
        assertThat(confirm.getStatusCode().value()).isEqualTo(201);

        // Still partitioned: unseeded stays 503 (whether via socket failure or an
        // opened "catalog" circuit breaker, the contract is the same ProblemDetail).
        ResponseEntity<Map<String, Object>> unseededAgain = postJson("/api/holds",
                Map.of("eventId", unseededEvent.toString(), "seatId", seat(1)), tokenB);
        assertThat(unseededAgain.getStatusCode().value()).isEqualTo(503);
        assertThat(String.valueOf(unseededAgain.getBody().get("detail")))
                .contains("Event catalog temporarily unavailable");

        // And a second buyer still books the seeded event's remaining seat.
        ResponseEntity<Map<String, Object>> hold2 = postJson("/api/holds",
                Map.of("eventId", seededEvent.toString(), "seatId", seat(2)), tokenB);
        assertThat(hold2.getStatusCode().value()).isEqualTo(201);
        ResponseEntity<Map<String, Object>> confirm2 = postJson("/api/bookings",
                Map.of("holdId", String.valueOf(hold2.getBody().get("holdId"))), tokenB);
        assertThat(confirm2.getStatusCode().value()).isEqualTo(201);

        assertNoOversell(seededEvent, capacity);
        Integer unseededRows = jdbc.queryForObject(
                "select count(*) from seat_inventory where event_id = ?", Integer.class, unseededEvent);
        assertThat(unseededRows)
                .as("a failed seeding attempt must not leave partial inventory behind").isZero();
        verdict("PARTITION_BOOKING_CATALOG",
                "unseeded events degraded to clean 503 ProblemDetail (real Feign gateway, "
                        + "blackholed catalog); seeded events booked 2/2 holds+confirms normally "
                        + "throughout the partition");
    }
}
