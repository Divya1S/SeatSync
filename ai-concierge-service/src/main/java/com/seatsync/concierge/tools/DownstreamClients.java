package com.seatsync.concierge.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Live HTTP calls to catalog-service and booking-service, each guarded by the
 * {@code downstream} circuit breaker. On any failure (connection refused, 5xx,
 * open breaker) the resilience4j fallback returns a
 * {@code "TOOL_UNAVAILABLE: <reason>"} string so the model can tell the user
 * the data is unavailable instead of fabricating it.
 *
 * <p>This is a separate bean from {@link ConciergeTools} so the resilience4j
 * AOP proxy is always invoked through normal Spring injection, independent of
 * how Spring AI reflects over the tool object.
 */
@Service
public class DownstreamClients {

    static final String TOOL_UNAVAILABLE_PREFIX = "TOOL_UNAVAILABLE: ";
    private static final int MAX_SEARCH_RESULTS = 5;

    private static final Logger log = LoggerFactory.getLogger(DownstreamClients.class);

    private final RestClient restClient;
    private final String catalogBaseUrl;
    private final String bookingBaseUrl;
    private final ObjectMapper objectMapper;

    public DownstreamClients(RestClient conciergeRestClient,
                             @Value("${concierge.catalog-base-url}") String catalogBaseUrl,
                             @Value("${concierge.booking-base-url}") String bookingBaseUrl,
                             ObjectMapper objectMapper) {
        this.restClient = conciergeRestClient;
        this.catalogBaseUrl = catalogBaseUrl;
        this.bookingBaseUrl = bookingBaseUrl;
        this.objectMapper = objectMapper;
    }

    /**
     * Catalog search, compacted to the top 5 results with only the fields the
     * model needs: id, name, venue, city, startsAt, priceFrom.
     */
    @CircuitBreaker(name = "downstream", fallbackMethod = "searchEventsUnavailable")
    public String searchEvents(String query, String city, String category) {
        URI uri = UriComponentsBuilder.fromUriString(catalogBaseUrl)
                .path("/api/catalog/events")
                .queryParam("q", nullToEmpty(query))
                .queryParam("city", nullToEmpty(city))
                .queryParam("category", nullToEmpty(category))
                .build()
                .toUri();
        JsonNode page = restClient.get().uri(uri).retrieve().body(JsonNode.class);
        ArrayNode results = objectMapper.createArrayNode();
        JsonNode content = page == null ? objectMapper.createArrayNode() : page.path("content");
        for (int i = 0; i < Math.min(MAX_SEARCH_RESULTS, content.size()); i++) {
            JsonNode event = content.get(i);
            ObjectNode compact = results.addObject();
            compact.put("id", event.path("id").asText());
            compact.put("name", event.path("name").asText());
            compact.put("venue", event.path("venue").path("name").asText());
            compact.put("city", event.path("venue").path("city").asText());
            compact.put("startsAt", event.path("startsAt").asText());
            compact.put("priceFrom", event.path("priceFrom").asDouble());
        }
        return results.toString();
    }

    /** Full event details from catalog, returned verbatim. */
    @CircuitBreaker(name = "downstream", fallbackMethod = "getEventDetailsUnavailable")
    public String getEventDetails(String eventId) {
        try {
            return restClient.get()
                    .uri(catalogBaseUrl + "/api/catalog/events/{id}", eventId)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.NotFound notFound) {
            // A 404 is an answer, not an outage — don't trip the breaker for it.
            return "NOT_FOUND: no event exists with id " + eventId;
        }
    }

    /** Booking seat view, summarized to counts by status. */
    @CircuitBreaker(name = "downstream", fallbackMethod = "getSeatAvailabilityUnavailable")
    public String getSeatAvailability(String eventId) {
        JsonNode payload = restClient.get()
                .uri(bookingBaseUrl + "/api/events/{id}/seats", eventId)
                .retrieve()
                .body(JsonNode.class);
        int total = 0;
        int available = 0;
        int held = 0;
        int booked = 0;
        if (payload != null) {
            for (JsonNode seat : payload.path("seats")) {
                total++;
                switch (seat.path("status").asText()) {
                    case "AVAILABLE" -> available++;
                    case "HELD" -> held++;
                    case "BOOKED" -> booked++;
                    default -> { /* unknown status: counted in total only */ }
                }
            }
        }
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("eventId", eventId);
        summary.put("total", total);
        summary.put("available", available);
        summary.put("held", held);
        summary.put("booked", booked);
        return summary.toString();
    }

    // --- resilience4j fallbacks (matching signatures + Throwable) ---

    String searchEventsUnavailable(String query, String city, String category, Throwable failure) {
        return unavailable("searchEvents", failure);
    }

    String getEventDetailsUnavailable(String eventId, Throwable failure) {
        return unavailable("getEventDetails", failure);
    }

    String getSeatAvailabilityUnavailable(String eventId, Throwable failure) {
        return unavailable("getSeatAvailability", failure);
    }

    private String unavailable(String tool, Throwable failure) {
        log.warn("Downstream call {} failed: {}", tool, failure.toString());
        return TOOL_UNAVAILABLE_PREFIX + failure.getMessage();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
