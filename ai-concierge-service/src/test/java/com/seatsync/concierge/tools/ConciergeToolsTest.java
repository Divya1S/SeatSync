package com.seatsync.concierge.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

/**
 * Tool behavior against mocked downstream HTTP services: compact top-5 search
 * results, seat summarization to counts, TOOL_UNAVAILABLE on failure (never an
 * exception into the LLM loop), and the per-request invocation flag.
 */
class ConciergeToolsTest {

    private static final String CATALOG = "http://catalog:8082";
    private static final String BOOKING = "http://booking:8083";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockRestServiceServer server;
    private ToolInvocationTracker tracker;
    private ConciergeTools tools;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        tracker = new ToolInvocationTracker();
        tracker.reset();
        DownstreamClients clients = new DownstreamClients(builder.build(), CATALOG, BOOKING, objectMapper);
        tools = new ConciergeTools(clients, tracker);
    }

    @Test
    void searchEventsReturnsCompactTopFive() throws Exception {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 7; i++) {
            if (i > 1) {
                content.append(',');
            }
            content.append("""
                    {"id":"id-%d","name":"Event %d","description":"long text that must not be returned",
                     "category":"CONCERT","status":"PUBLISHED","totalSeats":100,
                     "venue":{"id":"v1","name":"Grand Hall","city":"Berlin","address":"Main St 1"},
                     "startsAt":"2026-07-2%dT20:00:00Z","endsAt":"2026-07-2%dT23:00:00Z",
                     "priceFrom":25.00,"priceTo":99.00}""".formatted(i, i, i, i));
        }
        server.expect(requestTo(CATALOG + "/api/catalog/events?q=jazz&city=Berlin&category=CONCERT"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"content\":[" + content + "],\"totalElements\":7,\"totalPages\":1,\"number\":0,\"size\":20}",
                        MediaType.APPLICATION_JSON));

        String result = tools.searchEvents("jazz", "Berlin", "CONCERT");

        JsonNode parsed = objectMapper.readTree(result);
        assertThat(parsed.isArray()).isTrue();
        assertThat(parsed).hasSize(5);
        JsonNode first = parsed.get(0);
        assertThat(first.path("id").asText()).isEqualTo("id-1");
        assertThat(first.path("name").asText()).isEqualTo("Event 1");
        assertThat(first.path("venue").asText()).isEqualTo("Grand Hall");
        assertThat(first.path("city").asText()).isEqualTo("Berlin");
        assertThat(first.path("startsAt").asText()).isEqualTo("2026-07-21T20:00:00Z");
        assertThat(first.path("priceFrom").asDouble()).isEqualTo(25.00);
        assertThat(first.has("description")).isFalse();
        server.verify();
    }

    @Test
    void searchEventsSendsEmptyStringsForNullFilters() {
        server.expect(requestTo(CATALOG + "/api/catalog/events?q=&city=&category="))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"content\":[],\"totalElements\":0,\"totalPages\":0,\"number\":0,\"size\":20}",
                        MediaType.APPLICATION_JSON));

        String result = tools.searchEvents(null, null, null);

        assertThat(result).isEqualTo("[]");
        server.verify();
    }

    @Test
    void getSeatAvailabilitySummarizesToCounts() throws Exception {
        server.expect(requestTo(BOOKING + "/api/events/e-1/seats"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"eventId":"e-1","seats":[
                          {"seatId":"A-1-1","status":"AVAILABLE","mine":false},
                          {"seatId":"A-1-2","status":"AVAILABLE","mine":false},
                          {"seatId":"A-1-3","status":"AVAILABLE","mine":false},
                          {"seatId":"A-1-4","status":"HELD","mine":false,"holdExpiresAt":"2026-07-16T18:04:00Z"},
                          {"seatId":"A-1-5","status":"BOOKED","mine":false},
                          {"seatId":"A-1-6","status":"BOOKED","mine":false}
                        ]}""", MediaType.APPLICATION_JSON));

        String result = tools.getSeatAvailability("e-1");

        JsonNode parsed = objectMapper.readTree(result);
        assertThat(parsed.path("eventId").asText()).isEqualTo("e-1");
        assertThat(parsed.path("total").asInt()).isEqualTo(6);
        assertThat(parsed.path("available").asInt()).isEqualTo(3);
        assertThat(parsed.path("held").asInt()).isEqualTo(1);
        assertThat(parsed.path("booked").asInt()).isEqualTo(2);
        server.verify();
    }

    @Test
    void getEventDetailsReturnsBodyVerbatim() {
        String event = "{\"id\":\"e-9\",\"name\":\"Friday Night Jazz\",\"priceFrom\":25.00}";
        server.expect(requestTo(CATALOG + "/api/catalog/events/e-9"))
                .andExpect(method(GET))
                .andRespond(withSuccess(event, MediaType.APPLICATION_JSON));

        assertThat(tools.getEventDetails("e-9")).isEqualTo(event);
        server.verify();
    }

    @Test
    void getEventDetailsReportsNotFoundWithoutThrowing() {
        server.expect(requestTo(CATALOG + "/api/catalog/events/missing"))
                .andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        String result = tools.getEventDetails("missing");

        assertThat(result).startsWith("NOT_FOUND:").contains("missing");
    }

    @Test
    void downstreamFailureYieldsToolUnavailableNotException() {
        server.expect(requestTo(BOOKING + "/api/events/e-1/seats"))
                .andExpect(method(GET))
                .andRespond(withServerError());

        String result = tools.getSeatAvailability("e-1");

        assertThat(result).startsWith("TOOL_UNAVAILABLE:");
    }

    @Test
    void searchFailureYieldsToolUnavailableNotException() {
        server.expect(requestTo(CATALOG + "/api/catalog/events?q=jazz&city=&category="))
                .andExpect(method(GET))
                .andRespond(withServerError());

        String result = tools.searchEvents("jazz", null, null);

        assertThat(result).startsWith("TOOL_UNAVAILABLE:");
    }

    @Test
    void toolCallsFlipTheInvocationFlag() {
        assertThat(tracker.wasInvoked()).isFalse();
        server.expect(requestTo(BOOKING + "/api/events/e-1/seats"))
                .andRespond(withSuccess("{\"eventId\":\"e-1\",\"seats\":[]}", MediaType.APPLICATION_JSON));

        tools.getSeatAvailability("e-1");

        assertThat(tracker.wasInvoked()).isTrue();
        tracker.reset();
        assertThat(tracker.wasInvoked()).isFalse();
    }
}
