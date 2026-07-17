package com.seatsync.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-stack integration test against real Postgres and Redis containers:
 * organizer creates venue + event, publishes it, the public search finds it, GET by id is
 * served cache-aside from Redis on the second read, writes invalidate the cache, and an
 * attendee token is rejected (403) for writes.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.tracing.sampling.probability=0.0")
class CatalogServiceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final UUID organizerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final String organizerToken = TestTokens.accessToken(
            organizerId, "organizer@test.local", "Test Organizer", List.of("ORGANIZER"));
    private final String attendeeToken = TestTokens.accessToken(
            UUID.randomUUID(), "attendee@test.local", "Test Attendee", List.of("ATTENDEE"));

    @Test
    void organizerLifecycleWithCacheAside() {
        // --- organizer creates a venue ---
        ResponseEntity<JsonNode> venueResponse = exchange(HttpMethod.POST, "/api/catalog/venues",
                Map.of("name", "IT Test Arena", "city", "Austin", "address", "1 Test Way"),
                organizerToken);
        assertEquals(HttpStatus.CREATED, venueResponse.getStatusCode());
        String venueId = venueResponse.getBody().get("id").asText();

        // --- organizer creates an event with sections ---
        String eventName = "Testcontainers Tech Summit";
        Map<String, Object> createBody = Map.of(
                "name", eventName,
                "description", "Deep-dive talks on integration testing with real infrastructure.",
                "category", "WORKSHOP",
                "venueId", venueId,
                "startsAt", "2026-09-01T18:00:00Z",
                "endsAt", "2026-09-01T22:00:00Z",
                "sections", List.of(
                        Map.of("name", "A", "rowCount", 5, "seatsPerRow", 10,
                                "priceTier", "STANDARD", "price", 45.00),
                        Map.of("name", "B", "rowCount", 3, "seatsPerRow", 8,
                                "priceTier", "PREMIUM", "price", 75.00),
                        Map.of("name", "VIP", "rowCount", 1, "seatsPerRow", 6,
                                "priceTier", "VIP", "price", 120.00)));
        ResponseEntity<JsonNode> created = exchange(HttpMethod.POST, "/api/catalog/events",
                createBody, organizerToken);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        JsonNode event = created.getBody();
        String eventId = event.get("id").asText();
        assertEquals("DRAFT", event.get("status").asText());
        assertEquals(80, event.get("totalSeats").asInt());
        assertEquals(45.00, event.get("priceFrom").asDouble());
        assertEquals(120.00, event.get("priceTo").asDouble());
        assertEquals(venueId, event.get("venue").get("id").asText());
        assertEquals(organizerId.toString(), event.get("organizerId").asText());

        String eventKey = "catalog:event:" + eventId;
        String seatMapKey = "catalog:seatmap:" + eventId;

        // --- DRAFT: invisible to the public, never cached; visible to the owner ---
        assertEquals(HttpStatus.NOT_FOUND,
                exchange(HttpMethod.GET, "/api/catalog/events/" + eventId, null, null).getStatusCode());
        assertFalse(redisTemplate.hasKey(eventKey));
        ResponseEntity<JsonNode> ownerView = exchange(HttpMethod.GET,
                "/api/catalog/events/" + eventId, null, organizerToken);
        assertEquals(HttpStatus.OK, ownerView.getStatusCode());
        assertEquals("DRAFT", ownerView.getBody().get("status").asText());
        assertEquals(0, exchange(HttpMethod.GET, "/api/catalog/events?q=summit", null, null)
                .getBody().get("totalElements").asLong());

        // --- publish ---
        ResponseEntity<JsonNode> published = exchange(HttpMethod.POST,
                "/api/catalog/events/" + eventId + "/publish", null, organizerToken);
        assertEquals(HttpStatus.OK, published.getStatusCode());
        assertEquals("PUBLISHED", published.getBody().get("status").asText());

        // --- public search finds it (case-insensitive q, paginated shape) ---
        ResponseEntity<JsonNode> search = exchange(HttpMethod.GET,
                "/api/catalog/events?q=summit&category=WORKSHOP&city=Austin", null, null);
        assertEquals(HttpStatus.OK, search.getStatusCode());
        JsonNode page = search.getBody();
        assertEquals(1, page.get("totalElements").asLong());
        assertEquals(1, page.get("totalPages").asInt());
        assertEquals(0, page.get("number").asInt());
        assertEquals(20, page.get("size").asInt());
        assertEquals(eventName, page.get("content").get(0).get("name").asText());

        // --- first anonymous GET by id: read-through populates the cache with a TTL ---
        ResponseEntity<JsonNode> firstGet = exchange(HttpMethod.GET,
                "/api/catalog/events/" + eventId, null, null);
        assertEquals(HttpStatus.OK, firstGet.getStatusCode());
        assertEquals(eventName, firstGet.getBody().get("name").asText());
        assertTrue(redisTemplate.hasKey(eventKey), "event must be cached after the first public GET");
        Long ttl = redisTemplate.getExpire(eventKey);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 600, "cache TTL must be 600s, was " + ttl);

        // --- second GET is served from Redis: tamper with the cached JSON and observe it ---
        String cachedJson = redisTemplate.opsForValue().get(eventKey);
        assertNotNull(cachedJson);
        redisTemplate.opsForValue().set(eventKey,
                cachedJson.replace(eventName, eventName + " [cached]"), Duration.ofSeconds(600));
        ResponseEntity<JsonNode> secondGet = exchange(HttpMethod.GET,
                "/api/catalog/events/" + eventId, null, null);
        assertEquals(eventName + " [cached]", secondGet.getBody().get("name").asText(),
                "second GET must come from the Redis cache, not the database");

        // --- seat map: derived from sections, cached under its own key ---
        ResponseEntity<JsonNode> seatMap = exchange(HttpMethod.GET,
                "/api/catalog/events/" + eventId + "/seatmap", null, null);
        assertEquals(HttpStatus.OK, seatMap.getStatusCode());
        assertEquals(eventId, seatMap.getBody().get("eventId").asText());
        JsonNode sectionA = seatMap.getBody().get("sections").get(0);
        assertEquals("A", sectionA.get("name").asText());
        assertEquals(5, sectionA.get("rows").size());
        assertEquals("A-1-1", sectionA.get("rows").get(0).get("seats").get(0).get("seatId").asText());
        assertEquals(10, sectionA.get("rows").get(0).get("seats").size());
        assertTrue(redisTemplate.hasKey(seatMapKey));

        // --- update invalidates both cache keys ---
        Map<String, Object> updateBody = Map.of(
                "name", eventName + " Updated",
                "description", "Now with a second track.",
                "category", "WORKSHOP",
                "venueId", venueId,
                "startsAt", "2026-09-01T18:00:00Z",
                "endsAt", "2026-09-01T22:00:00Z");
        ResponseEntity<JsonNode> updated = exchange(HttpMethod.PUT,
                "/api/catalog/events/" + eventId, updateBody, organizerToken);
        assertEquals(HttpStatus.OK, updated.getStatusCode());
        assertFalse(redisTemplate.hasKey(eventKey), "update must evict the event cache key");
        assertFalse(redisTemplate.hasKey(seatMapKey), "update must evict the seatmap cache key");

        // --- next public GET sees fresh data from the database again ---
        ResponseEntity<JsonNode> afterUpdate = exchange(HttpMethod.GET,
                "/api/catalog/events/" + eventId, null, null);
        assertEquals(eventName + " Updated", afterUpdate.getBody().get("name").asText());
        assertTrue(redisTemplate.hasKey(eventKey));

        // --- events/mine returns the organizer's own events ---
        ResponseEntity<JsonNode> mine = exchange(HttpMethod.GET, "/api/catalog/events/mine",
                null, organizerToken);
        assertEquals(HttpStatus.OK, mine.getStatusCode());
        assertEquals(1, mine.getBody().size());
        assertEquals(eventId, mine.getBody().get(0).get("id").asText());

        // --- DELETE is a soft cancel and evicts the cache ---
        assertEquals(HttpStatus.NO_CONTENT, exchange(HttpMethod.DELETE,
                "/api/catalog/events/" + eventId, null, organizerToken).getStatusCode());
        assertFalse(redisTemplate.hasKey(eventKey));
        assertEquals("CANCELLED", exchange(HttpMethod.GET, "/api/catalog/events/" + eventId,
                null, organizerToken).getBody().get("status").asText());
        assertEquals(HttpStatus.NOT_FOUND, exchange(HttpMethod.GET,
                "/api/catalog/events/" + eventId, null, null).getStatusCode());
    }

    @Test
    void attendeeTokenIsForbiddenToCreate() {
        ResponseEntity<JsonNode> eventAttempt = exchange(HttpMethod.POST, "/api/catalog/events",
                Map.of("name", "Nope"), attendeeToken);
        assertEquals(HttpStatus.FORBIDDEN, eventAttempt.getStatusCode());
        assertEquals(403, eventAttempt.getBody().get("status").asInt());
        assertEquals("Forbidden", eventAttempt.getBody().get("title").asText());

        ResponseEntity<JsonNode> venueAttempt = exchange(HttpMethod.POST, "/api/catalog/venues",
                Map.of("name", "Nope", "city", "Austin", "address", "1 No Way"), attendeeToken);
        assertEquals(HttpStatus.FORBIDDEN, venueAttempt.getStatusCode());

        ResponseEntity<JsonNode> mineAttempt = exchange(HttpMethod.GET, "/api/catalog/events/mine",
                null, attendeeToken);
        assertEquals(HttpStatus.FORBIDDEN, mineAttempt.getStatusCode());
    }

    @Test
    void anonymousAccessRules() {
        // writes require a token → 401 ProblemDetail
        ResponseEntity<JsonNode> anonymousCreate = exchange(HttpMethod.POST, "/api/catalog/venues",
                Map.of("name", "Nope", "city", "Austin", "address", "1 No Way"), null);
        assertEquals(HttpStatus.UNAUTHORIZED, anonymousCreate.getStatusCode());
        assertEquals(401, anonymousCreate.getBody().get("status").asInt());

        // public reads work anonymously; the startup seed provided venues + published events
        ResponseEntity<JsonNode> venues = exchange(HttpMethod.GET, "/api/catalog/venues", null, null);
        assertEquals(HttpStatus.OK, venues.getStatusCode());
        assertTrue(venues.getBody().size() >= 2);

        ResponseEntity<JsonNode> jazz = exchange(HttpMethod.GET,
                "/api/catalog/events?q=friday+night+jazz&category=CONCERT&city=Austin", null, null);
        assertEquals(HttpStatus.OK, jazz.getStatusCode());
        assertEquals(1, jazz.getBody().get("totalElements").asLong());
        JsonNode seeded = jazz.getBody().get("content").get(0);
        assertEquals("Friday Night Jazz", seeded.get("name").asText());
        assertEquals("00000000-0000-0000-0000-000000000002", seeded.get("organizerId").asText());

        // health is public
        assertEquals(HttpStatus.OK,
                exchange(HttpMethod.GET, "/actuator/health", null, null).getStatusCode());
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
