package com.seatsync.booking.contracts;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import au.com.dius.pact.provider.spring.junit5.PactVerificationSpringProvider;
import com.seatsync.booking.support.AbstractBookingIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Pact provider verification: booking-service is the provider of the
 * committed {@code contracts/pacts/ai-concierge-service-booking-service.json}
 * (the public live-seats view, {@code GET /api/events/{id}/seats}).
 *
 * <p>Runs against the full application on a random port with the shared
 * Testcontainers infrastructure ({@link AbstractBookingIT}); the {@code @State}
 * handler seeds real {@code seat_inventory} rows for the event id supplied in
 * the pact's provider-state params. {@code @PactFolder} loads every pact in
 * the folder whose provider is {@code booking-service}, so ALL interactions
 * of every consumer are verified here.
 */
@Provider("booking-service")
@PactFolder("../contracts/pacts")
class BookingProviderPactIT extends AbstractBookingIT {

    /** Fallback matching the fixed example id in the committed pact. */
    private static final UUID DEFAULT_EVENT_ID = UUID.fromString("b7f4a9d2-3c61-48e5-9f0a-2d8c5b7e1a3f");

    @LocalServerPort
    private int port;

    @BeforeEach
    void setTarget(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpringProvider.class)
    void verifiesPactInteractions(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @State("an event with seat inventory exists")
    void eventWithSeatInventoryExists(Map<String, Object> params) {
        UUID eventId = params != null && params.get("eventId") != null
                ? UUID.fromString(String.valueOf(params.get("eventId")))
                : DEFAULT_EVENT_ID;

        // Reset anything a previous test left behind for this event, then seed
        // three AVAILABLE seats — no active holds, so an anonymous caller sees
        // status AVAILABLE and mine=false, as the pact's fixed examples show.
        jdbc.update("DELETE FROM holds WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM bookings WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM seat_inventory WHERE event_id = ?", eventId);
        for (int n = 1; n <= 3; n++) {
            jdbc.update("""
                    INSERT INTO seat_inventory
                        (id, event_id, seat_id, section, row_label, seat_number, price, status, version)
                    VALUES (?, ?, ?, 'A', '1', ?, ?, 'AVAILABLE', 0)
                    """,
                    UUID.randomUUID(), eventId, "A-1-" + n, n, new BigDecimal("49.00"));
        }
    }
}
