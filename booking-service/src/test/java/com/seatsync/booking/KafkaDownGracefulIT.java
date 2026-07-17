package com.seatsync.booking;

import com.seatsync.booking.catalog.CatalogGateway;
import com.seatsync.booking.catalog.EventDto;
import com.seatsync.booking.catalog.SeatMapDto;
import com.seatsync.booking.support.TestContainersHolder;
import com.seatsync.booking.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

/**
 * Graceful degradation: with Kafka bootstrap pointing at an unreachable address
 * (localhost:1), booking confirm must still return 201 quickly — the request
 * path never touches Kafka. The BookingConfirmed event is PARKED in the
 * transactional outbox (published_at NULL) instead of being lost; the relay
 * retries it until a broker accepts.
 *
 * <p>This test runs against its OWN database on the shared Postgres container
 * (not {@code @ServiceConnection}): outbox relays running in other cached
 * Spring test contexts have a live Kafka and would otherwise publish the rows
 * this test asserts stay parked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.properties.request.timeout.ms=1000",
        "spring.kafka.admin.properties.default.api.timeout.ms=1000",
        "management.tracing.sampling.probability=0.0",
        "management.zipkin.tracing.export.enabled=false"
})
class KafkaDownGracefulIT {

    private static final String ISOLATED_DB = "seatsync_kafkadown";

    static final PostgreSQLContainer<?> POSTGRES = TestContainersHolder.POSTGRES;

    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry registry) {
        createIsolatedDatabase();
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost()
                + ":" + POSTGRES.getMappedPort(5432) + "/" + ISOLATED_DB);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", TestContainersHolder.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> TestContainersHolder.REDIS.getMappedPort(6379).toString());
    }

    private static void createIsolatedDatabase() {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + ISOLATED_DB);
        } catch (SQLException e) {
            // Already exists (context re-created in the same JVM) — fine.
        }
    }

    @MockitoBean
    private CatalogGateway catalogGateway;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void bookingStillSucceedsQuicklyWhileKafkaIsUnreachable() {
        UUID eventId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("30.00");
        when(catalogGateway.getEvent(eventId)).thenReturn(new EventDto(eventId, "Kafka Down Fest", "PUBLISHED"));
        SeatMapDto.Row row = new SeatMapDto.Row("1", List.of(
                new SeatMapDto.Seat("A-1-1", 1, price),
                new SeatMapDto.Seat("A-1-2", 2, price)));
        when(catalogGateway.getSeatMap(eventId)).thenReturn(new SeatMapDto(eventId,
                List.of(new SeatMapDto.Section("A", "STANDARD", price, List.of(row)))));

        String token = TestTokens.attendee(UUID.randomUUID(), "resilient@test.io");

        ResponseEntity<Map<String, Object>> hold = postJson("/api/holds",
                Map.of("eventId", eventId.toString(), "seatId", "A-1-1"), token);
        assertThat(hold.getStatusCode().value()).isEqualTo(201);
        String holdId = String.valueOf(hold.getBody().get("holdId"));

        long startNanos = System.nanoTime();
        ResponseEntity<Map<String, Object>> confirm = postJson("/api/bookings",
                Map.of("holdId", holdId), token);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(confirm.getStatusCode().value())
                .as("booking must succeed although Kafka is down")
                .isEqualTo(201);
        assertThat(elapsedMs)
                .as("Kafka failure must be fail-fast, not a hanging request")
                .isLessThan(5000);
        assertThat(confirm.getBody().get("status")).isEqualTo("CONFIRMED");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Integer confirmed = jdbc.queryForObject(
                    "select count(*) from bookings where event_id = ? and status = 'CONFIRMED'",
                    Integer.class, eventId);
            assertThat(confirmed).isEqualTo(1);
        });

        // The event is PARKED in the outbox, not lost: the row exists, carries
        // messageId = its own id in the payload, and stays unpublished for as
        // long as the broker is unreachable.
        Map<String, Object> outboxRow = jdbc.queryForMap(
                "select id, payload, published_at from outbox_events "
                        + "where topic = 'seatsync.booking.confirmed' and message_key = ?",
                eventId.toString());
        assertThat(outboxRow.get("published_at"))
                .as("event must be parked (published_at NULL) while the broker is unreachable")
                .isNull();
        assertThat(String.valueOf(outboxRow.get("payload")))
                .contains("\"messageId\":\"" + outboxRow.get("id") + "\"")
                .contains("\"type\":\"BookingConfirmed\"")
                .contains("\"seatId\":\"A-1-1\"");
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, Object body, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearerToken);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }
}
