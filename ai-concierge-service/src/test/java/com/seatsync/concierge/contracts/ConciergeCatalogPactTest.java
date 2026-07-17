package com.seatsync.concierge.contracts;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsync.concierge.tools.DownstreamClients;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer-driven contract: ai-concierge-service &rarr; catalog-service.
 *
 * <p>The interactions are driven through the REAL {@link DownstreamClients}
 * (its RestClient pointed at the Pact mock server), so the pact records
 * exactly what the concierge sends (all three query params are always
 * present, blank when the model omits a filter) and what it reads back.
 * Data fields use type/regex matchers with FIXED example values so the
 * committed pact file regenerates byte-identically; structure is exact.
 *
 * <p>The generated pact is committed to
 * {@code contracts/pacts/ai-concierge-service-catalog-service.json};
 * catalog-service verifies it with {@code @PactFolder}.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = ConciergeCatalogPactTest.PROVIDER, pactVersion = PactSpecVersion.V4)
class ConciergeCatalogPactTest {

    static final String PROVIDER = "catalog-service";
    static final String CONSUMER = "ai-concierge-service";

    /** ISO-8601 UTC instant, optionally with fractional seconds (§6 of CONVENTIONS.md). */
    static final String ISO_INSTANT_REGEX = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

    private static final String EVENT_ID = "b7f4a9d2-3c61-48e5-9f0a-2d8c5b7e1a3f";
    private static final String MISSING_EVENT_ID = "de1e7ed0-a2b4-4c6d-8e0f-1a3b5c7d9e0f";
    private static final String VENUE_ID = "4e9d8c7b-6a51-4f3e-b2d1-0c9e8f7a6b5c";
    private static final String ORGANIZER_ID = "00000000-0000-0000-0000-000000000002";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Pact(provider = PROVIDER, consumer = CONSUMER)
    V4Pact searchEventsPact(PactDslWithProvider builder) {
        DslPart page = new PactDslJsonBody()
                .integerType("totalElements", 1)
                .eachLike("content")
                    .uuid("id", EVENT_ID)
                    .stringType("name", "Friday Night Jazz")
                    .stringMatcher("startsAt", ISO_INSTANT_REGEX, "2026-07-24T20:00:00Z")
                    .decimalType("priceFrom", 25.00)
                    .object("venue")
                        .stringType("name", "Grand Hall")
                        .stringType("city", "Berlin")
                    .closeObject()
                .closeObject()
                .closeArray();
        return builder
                .given("published events exist")
                .uponReceiving("a search for published events with blank q, city and category filters")
                .path("/api/catalog/events")
                .method("GET")
                .query("q=&city=&category=")
                .willRespondWith()
                .status(200)
                .body(page)
                .toPact(V4Pact.class);
    }

    @Pact(provider = PROVIDER, consumer = CONSUMER)
    V4Pact eventDetailsPact(PactDslWithProvider builder) {
        DslPart event = new PactDslJsonBody()
                .uuid("id", EVENT_ID)
                .stringType("name", "Friday Night Jazz")
                .stringType("description", "Smooth jazz to close out the week")
                .stringMatcher("category", "CONCERT|WORKSHOP|SPORTS|CAMPUS|OTHER", "CONCERT")
                .stringMatcher("startsAt", ISO_INSTANT_REGEX, "2026-07-24T20:00:00Z")
                .stringMatcher("endsAt", ISO_INSTANT_REGEX, "2026-07-24T23:00:00Z")
                .stringMatcher("status", "DRAFT|PUBLISHED|CANCELLED", "PUBLISHED")
                .uuid("organizerId", ORGANIZER_ID)
                .integerType("totalSeats", 100)
                .decimalType("priceFrom", 25.00)
                .decimalType("priceTo", 99.00)
                .object("venue")
                    .uuid("id", VENUE_ID)
                    .stringType("name", "Grand Hall")
                    .stringType("city", "Berlin")
                    .stringType("address", "Main St 1")
                .closeObject();
        return builder
                .given("an event exists", Map.of("eventId", EVENT_ID))
                .uponReceiving("a request for an existing event by id")
                .path("/api/catalog/events/" + EVENT_ID)
                .method("GET")
                .willRespondWith()
                .status(200)
                .body(event)
                .toPact(V4Pact.class);
    }

    @Pact(provider = PROVIDER, consumer = CONSUMER)
    V4Pact missingEventPact(PactDslWithProvider builder) {
        return builder
                .given("event does not exist", Map.of("eventId", MISSING_EVENT_ID))
                .uponReceiving("a request for a missing event by id")
                .path("/api/catalog/events/" + MISSING_EVENT_ID)
                .method("GET")
                .willRespondWith()
                .status(404)
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "searchEventsPact", pactVersion = PactSpecVersion.V4)
    void searchEventsSendsAllFiltersAndReadsThePage(MockServer mockServer) throws Exception {
        String result = clients(mockServer).searchEvents(null, null, null);

        JsonNode compact = objectMapper.readTree(result);
        assertThat(compact.isArray()).isTrue();
        assertThat(compact).hasSize(1);
        JsonNode first = compact.get(0);
        assertThat(first.path("id").asText()).isEqualTo(EVENT_ID);
        assertThat(first.path("name").asText()).isEqualTo("Friday Night Jazz");
        assertThat(first.path("venue").asText()).isEqualTo("Grand Hall");
        assertThat(first.path("city").asText()).isEqualTo("Berlin");
        assertThat(first.path("startsAt").asText()).isEqualTo("2026-07-24T20:00:00Z");
        assertThat(first.path("priceFrom").asDouble()).isEqualTo(25.00);
    }

    @Test
    @PactTestFor(pactMethod = "eventDetailsPact", pactVersion = PactSpecVersion.V4)
    void eventDetailsReadsTheFullEventShape(MockServer mockServer) throws Exception {
        String result = clients(mockServer).getEventDetails(EVENT_ID);

        JsonNode event = objectMapper.readTree(result);
        assertThat(event.path("id").asText()).isEqualTo(EVENT_ID);
        assertThat(event.path("name").asText()).isEqualTo("Friday Night Jazz");
        assertThat(event.path("category").asText()).isEqualTo("CONCERT");
        assertThat(event.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(event.path("venue").path("city").asText()).isEqualTo("Berlin");
        assertThat(event.path("totalSeats").asInt()).isEqualTo(100);
        assertThat(event.path("priceFrom").asDouble()).isEqualTo(25.00);
        assertThat(event.path("priceTo").asDouble()).isEqualTo(99.00);
    }

    @Test
    @PactTestFor(pactMethod = "missingEventPact", pactVersion = PactSpecVersion.V4)
    void missingEventIsMappedToNotFoundAnswer(MockServer mockServer) {
        String result = clients(mockServer).getEventDetails(MISSING_EVENT_ID);

        assertThat(result).startsWith("NOT_FOUND:").contains(MISSING_EVENT_ID);
    }

    /** The real production consumer code, pointed at the Pact mock server. */
    private DownstreamClients clients(MockServer mockServer) {
        return new DownstreamClients(RestClient.create(), mockServer.getUrl(), mockServer.getUrl(), objectMapper);
    }
}
