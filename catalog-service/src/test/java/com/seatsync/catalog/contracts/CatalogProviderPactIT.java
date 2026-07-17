package com.seatsync.catalog.contracts;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import au.com.dius.pact.provider.spring.junit5.PactVerificationSpringProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Provider-side verification of every committed consumer pact that names catalog-service
 * as the provider (CONVENTIONS §8.1): the pacts are loaded from contracts/pacts/ and
 * replayed against the real application running on a random port with real Postgres and
 * Redis containers.
 *
 * <p>Provider states are shared by mandate across all consumers:
 * <ul>
 *   <li>{@code "published events exist"} — at least two PUBLISHED events with sections</li>
 *   <li>{@code "an event exists"} (param {@code eventId}) — one PUBLISHED event with sections
 *       under exactly that UUID, so both GET /api/catalog/events/{id} and
 *       GET /api/catalog/events/{id}/seatmap verify</li>
 *   <li>{@code "event does not exist"} (param {@code eventId}) — guaranteed absence</li>
 * </ul>
 *
 * <p>Seeding uses native SQL: the entities generate their UUIDs ({@code @GeneratedValue}
 * + {@code @UuidGenerator}), so repository {@code save()} with a pre-assigned id would go
 * through {@code merge()} rather than a plain insert — direct SQL is the deterministic way
 * to persist the EXACT event id a pact pins in its request path. Every state handler also
 * flushes the {@code catalog:*} Redis keys so no interaction is served a stale cached view
 * of a previous interaction's state.
 */
@Provider("catalog-service")
@PactFolder("../contracts/pacts")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.tracing.sampling.probability=0.0")
class CatalogProviderPactIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    /** Fixed ids so every state handler is idempotent (delete + insert on the same rows). */
    private static final UUID PACT_VENUE_ID = UUID.fromString("4e9d8c7b-6a51-4f3e-b2d1-0c9e8f7a6b5c");
    private static final UUID PUBLISHED_EVENT_ONE = UUID.fromString("aaaaaaa1-0000-4000-8000-000000000001");
    private static final UUID PUBLISHED_EVENT_TWO = UUID.fromString("aaaaaaa2-0000-4000-8000-000000000002");
    private static final UUID DEFAULT_EVENT_ID = UUID.fromString("b7f4a9d2-3c61-48e5-9f0a-2d8c5b7e1a3f");
    private static final UUID ORGANIZER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setTarget(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpringProvider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @State("published events exist")
    void publishedEventsExist() {
        flushCatalogCache();
        seedPublishedEvent(PUBLISHED_EVENT_ONE, "Pact Symphony Night",
                "An orchestral evening seeded for contract verification",
                Instant.parse("2026-08-14T19:30:00Z"), Instant.parse("2026-08-14T22:30:00Z"));
        seedPublishedEvent(PUBLISHED_EVENT_TWO, "Pact Riverside Sessions",
                "Open-air acoustic sessions seeded for contract verification",
                Instant.parse("2026-08-21T18:00:00Z"), Instant.parse("2026-08-21T21:00:00Z"));
    }

    @State("an event exists")
    void anEventExists(Map<String, Object> params) {
        flushCatalogCache();
        UUID eventId = eventIdFrom(params, DEFAULT_EVENT_ID);
        seedPublishedEvent(eventId, "Friday Night Jazz",
                "Smooth jazz to close out the week",
                Instant.parse("2026-07-24T20:00:00Z"), Instant.parse("2026-07-24T23:00:00Z"));
    }

    @State("event does not exist")
    void eventDoesNotExist(Map<String, Object> params) {
        flushCatalogCache();
        UUID eventId = eventIdFrom(params, null);
        if (eventId != null) {
            // sections cascade via FK ON DELETE CASCADE
            jdbc.update("DELETE FROM events WHERE id = ?", eventId);
        }
    }

    private static UUID eventIdFrom(Map<String, Object> params, UUID fallback) {
        if (params == null || params.get("eventId") == null) {
            return fallback;
        }
        return UUID.fromString(params.get("eventId").toString());
    }

    /**
     * Idempotently (re)creates a PUBLISHED event with the EXACT given id, a shared venue and
     * two sections, so the full event shape (venue, totalSeats, priceFrom/priceTo) and the
     * derived seat map both materialize.
     */
    private void seedPublishedEvent(UUID eventId, String name, String description,
                                    Instant startsAt, Instant endsAt) {
        jdbc.update("""
                INSERT INTO venues (id, name, city, address) VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, PACT_VENUE_ID, "Grand Hall", "Berlin", "Main St 1");
        jdbc.update("DELETE FROM events WHERE id = ?", eventId);
        jdbc.update("""
                INSERT INTO events (id, name, description, category, venue_id,
                                    starts_at, ends_at, status, organizer_id)
                VALUES (?, ?, ?, 'CONCERT', ?, ?, ?, 'PUBLISHED', ?)
                """, eventId, name, description, PACT_VENUE_ID,
                OffsetDateTime.ofInstant(startsAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(endsAt, ZoneOffset.UTC),
                ORGANIZER_ID);
        insertSection(eventId, "A", "STANDARD", new BigDecimal("25.00"));
        insertSection(eventId, "VIP", "VIP", new BigDecimal("99.00"));
    }

    private void insertSection(UUID eventId, String name, String priceTier, BigDecimal price) {
        jdbc.update("""
                INSERT INTO sections (id, event_id, name, price_tier, price, row_count, seats_per_row)
                VALUES (?, ?, ?, ?, ?, 5, 10)
                """, UUID.randomUUID(), eventId, name, priceTier, price);
    }

    /** Redis cache-aside must never serve one interaction the previous interaction's state. */
    private void flushCatalogCache() {
        Set<String> keys = redisTemplate.keys("catalog:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
