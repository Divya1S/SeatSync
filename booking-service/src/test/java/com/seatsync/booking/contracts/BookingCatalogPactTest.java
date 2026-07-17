package com.seatsync.booking.contracts;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.seatsync.booking.catalog.CatalogClient;
import com.seatsync.booking.catalog.CatalogGateway;
import com.seatsync.booking.catalog.EventDto;
import com.seatsync.booking.catalog.SeatMapDto;
import feign.FeignException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Consumer-driven contract: booking-service &rarr; catalog-service
 * (event by id + seatmap, consumed by inventory seeding).
 *
 * <p>The interactions are driven through the REAL OpenFeign client
 * ({@link CatalogClient}, built by Spring Cloud OpenFeign with
 * {@code catalog.base-url} pointed at the Pact mock server) wrapped in the
 * REAL {@link CatalogGateway}. The gateway is instantiated directly rather
 * than proxied, so its resilience4j {@code @Retry}/{@code @CircuitBreaker}
 * annotations are inert here — no breaker or retry interference with the
 * mock-server matching. Data fields use type/regex matchers with FIXED
 * example values so the committed pact file regenerates byte-identically.
 *
 * <p>Provider state names are SHARED with the concierge&rarr;catalog pact
 * ("an event exists" / "event does not exist"), so catalog-service implements
 * a single set of {@code @State} handlers for all of its consumers.
 *
 * <p>The generated pact is committed to
 * {@code contracts/pacts/booking-service-catalog-service.json};
 * catalog-service verifies it with {@code @PactFolder}.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = BookingCatalogPactTest.PROVIDER, pactVersion = PactSpecVersion.V4)
class BookingCatalogPactTest {

    static final String PROVIDER = "catalog-service";
    static final String CONSUMER = "booking-service";

    /** ISO-8601 UTC instant, optionally with fractional seconds (§6 of CONVENTIONS.md). */
    static final String ISO_INSTANT_REGEX = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";
    /** Seat identity format "<section>-<row>-<number>" (§6 of CONVENTIONS.md). */
    static final String SEAT_ID_REGEX = "[A-Za-z0-9]+-[A-Za-z0-9]+-\\d+";

    // Same fixed example ids as the concierge->catalog pact, so catalog's
    // shared provider-state handlers can seed one canonical event.
    private static final String EVENT_ID = "b7f4a9d2-3c61-48e5-9f0a-2d8c5b7e1a3f";
    private static final String MISSING_EVENT_ID = "de1e7ed0-a2b4-4c6d-8e0f-1a3b5c7d9e0f";
    private static final String VENUE_ID = "4e9d8c7b-6a51-4f3e-b2d1-0c9e8f7a6b5c";
    private static final String ORGANIZER_ID = "00000000-0000-0000-0000-000000000002";

    private ConfigurableApplicationContext feignContext;

    @AfterEach
    void closeFeignContext() {
        if (feignContext != null) {
            feignContext.close();
        }
    }

    @Pact(provider = PROVIDER, consumer = CONSUMER)
    V4Pact publishedEventPact(PactDslWithProvider builder) {
        // Full §6.2 Event shape. Booking's EventDto only reads id/name/status;
        // status is pinned to PUBLISHED because inventory seeding refuses
        // anything else — for this state the event MUST be published.
        DslPart event = new PactDslJsonBody()
                .uuid("id", EVENT_ID)
                .stringType("name", "Friday Night Jazz")
                .stringType("description", "Smooth jazz to close out the week")
                .stringMatcher("category", "CONCERT|WORKSHOP|SPORTS|CAMPUS|OTHER", "CONCERT")
                .stringMatcher("startsAt", ISO_INSTANT_REGEX, "2026-07-24T20:00:00Z")
                .stringMatcher("endsAt", ISO_INSTANT_REGEX, "2026-07-24T23:00:00Z")
                .stringMatcher("status", "PUBLISHED", "PUBLISHED")
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
                .uponReceiving("a request for a published event by id before seeding inventory")
                .path("/api/catalog/events/" + EVENT_ID)
                .method("GET")
                .willRespondWith()
                .status(200)
                .body(event)
                .toPact(V4Pact.class);
    }

    @Pact(provider = PROVIDER, consumer = CONSUMER)
    V4Pact seatMapPact(PactDslWithProvider builder) {
        DslPart seatMap = new PactDslJsonBody()
                .uuid("eventId", EVENT_ID)
                .eachLike("sections")
                    .stringType("name", "A")
                    .stringMatcher("priceTier", "STANDARD|PREMIUM|VIP", "STANDARD")
                    .decimalType("price", 49.00)
                    .eachLike("rows")
                        .stringType("label", "1")
                        .eachLike("seats")
                            .stringMatcher("seatId", SEAT_ID_REGEX, "A-1-1")
                            .integerType("number", 1)
                            .decimalType("price", 49.00)
                        .closeObject()
                        .closeArray()
                    .closeObject()
                    .closeArray()
                .closeObject()
                .closeArray();
        return builder
                .given("an event exists", Map.of("eventId", EVENT_ID))
                .uponReceiving("a request for the seat map of an existing event")
                .path("/api/catalog/events/" + EVENT_ID + "/seatmap")
                .method("GET")
                .willRespondWith()
                .status(200)
                .body(seatMap)
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
    @PactTestFor(pactMethod = "publishedEventPact", pactVersion = PactSpecVersion.V4)
    void readsThePublishedEventFieldsBookingConsumes(MockServer mockServer) {
        EventDto event = catalogGateway(mockServer).getEvent(UUID.fromString(EVENT_ID));

        assertThat(event.id()).isEqualTo(UUID.fromString(EVENT_ID));
        assertThat(event.name()).isEqualTo("Friday Night Jazz");
        assertThat(event.status()).isEqualTo("PUBLISHED");
    }

    @Test
    @PactTestFor(pactMethod = "seatMapPact", pactVersion = PactSpecVersion.V4)
    void readsTheSeatMapShapeInventorySeedingConsumes(MockServer mockServer) {
        SeatMapDto seatMap = catalogGateway(mockServer).getSeatMap(UUID.fromString(EVENT_ID));

        assertThat(seatMap.eventId()).isEqualTo(UUID.fromString(EVENT_ID));
        assertThat(seatMap.sections()).hasSize(1);
        SeatMapDto.Section section = seatMap.sections().get(0);
        assertThat(section.name()).isEqualTo("A");
        assertThat(section.priceTier()).isEqualTo("STANDARD");
        assertThat(section.price()).isEqualByComparingTo("49.0");
        assertThat(section.rows()).hasSize(1);
        SeatMapDto.Row row = section.rows().get(0);
        assertThat(row.label()).isEqualTo("1");
        assertThat(row.seats()).hasSize(1);
        SeatMapDto.Seat seat = row.seats().get(0);
        assertThat(seat.seatId()).isEqualTo("A-1-1");
        assertThat(seat.number()).isEqualTo(1);
        assertThat(seat.price()).isEqualByComparingTo("49.0");
    }

    @Test
    @PactTestFor(pactMethod = "missingEventPact", pactVersion = PactSpecVersion.V4)
    void missingEventSurfacesAsFeignNotFound(MockServer mockServer) {
        CatalogGateway gateway = catalogGateway(mockServer);
        UUID missing = UUID.fromString(MISSING_EVENT_ID);

        // InventoryService maps this to a 404 ProblemDetail ("not found in catalog").
        assertThatExceptionOfType(FeignException.NotFound.class)
                .isThrownBy(() -> gateway.getEvent(missing));
    }

    /**
     * The real production consumer code: a Spring Cloud OpenFeign-built
     * {@link CatalogClient} (same annotations, contract, encoder/decoder as at
     * runtime) with {@code catalog.base-url} pointed at the Pact mock server,
     * wrapped in the real {@link CatalogGateway}.
     */
    private CatalogGateway catalogGateway(MockServer mockServer) {
        feignContext = new SpringApplicationBuilder(FeignTestConfig.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                // command-line arg, NOT .properties(): default properties would
                // lose to application.yml's catalog.base-url (localhost:8082)
                .run("--catalog.base-url=" + mockServer.getUrl());
        return new CatalogGateway(feignContext.getBean(CatalogClient.class));
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({FeignAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class})
    @EnableFeignClients(clients = CatalogClient.class)
    static class FeignTestConfig {
    }
}
