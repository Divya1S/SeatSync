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
 * Consumer-driven contract: ai-concierge-service &rarr; booking-service
 * (public live-seats view, summarized by the concierge to status counts).
 *
 * <p>Driven through the REAL {@link DownstreamClients} against the Pact mock
 * server; type/regex matchers with fixed examples keep the committed file
 * deterministic. Committed to
 * {@code contracts/pacts/ai-concierge-service-booking-service.json};
 * booking-service verifies it with {@code @PactFolder}.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = ConciergeBookingPactTest.PROVIDER, pactVersion = PactSpecVersion.V4)
class ConciergeBookingPactTest {

    static final String PROVIDER = "booking-service";
    static final String CONSUMER = "ai-concierge-service";

    /** Seat identity format "<section>-<row>-<number>" (§6 of CONVENTIONS.md). */
    static final String SEAT_ID_REGEX = "[A-Za-z0-9]+-[A-Za-z0-9]+-\\d+";

    private static final String EVENT_ID = "b7f4a9d2-3c61-48e5-9f0a-2d8c5b7e1a3f";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Pact(provider = PROVIDER, consumer = CONSUMER)
    V4Pact liveSeatsPact(PactDslWithProvider builder) {
        DslPart seatView = new PactDslJsonBody()
                .uuid("eventId", EVENT_ID)
                .eachLike("seats")
                    .stringMatcher("seatId", SEAT_ID_REGEX, "A-1-1")
                    .stringMatcher("status", "AVAILABLE|HELD|BOOKED", "AVAILABLE")
                    .booleanType("mine", false)
                .closeObject()
                .closeArray();
        return builder
                .given("an event with seat inventory exists", Map.of("eventId", EVENT_ID))
                .uponReceiving("an anonymous request for the live seat view of an event")
                .path("/api/events/" + EVENT_ID + "/seats")
                .method("GET")
                .willRespondWith()
                .status(200)
                .body(seatView)
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "liveSeatsPact", pactVersion = PactSpecVersion.V4)
    void seatAvailabilityIsSummarizedToCounts(MockServer mockServer) throws Exception {
        DownstreamClients clients =
                new DownstreamClients(RestClient.create(), mockServer.getUrl(), mockServer.getUrl(), objectMapper);

        String result = clients.getSeatAvailability(EVENT_ID);

        JsonNode summary = objectMapper.readTree(result);
        assertThat(summary.path("eventId").asText()).isEqualTo(EVENT_ID);
        assertThat(summary.path("total").asInt()).isEqualTo(1);
        assertThat(summary.path("available").asInt()).isEqualTo(1);
        assertThat(summary.path("held").asInt()).isZero();
        assertThat(summary.path("booked").asInt()).isZero();
    }
}
