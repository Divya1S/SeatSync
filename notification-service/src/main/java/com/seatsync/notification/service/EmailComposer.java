package com.seatsync.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Turns a raw event payload (§6.4) into a human-friendly email, keyed on the
 * payload's {@code type} field.
 */
@Component
public class EmailComposer {

    public static final String TYPE_BOOKING_CONFIRMED = "BookingConfirmed";
    public static final String TYPE_HOLD_EXPIRED = "HoldExpired";
    public static final String TYPE_WAITLIST_OFFERED = "WaitlistOffered";

    private static final DateTimeFormatter HUMAN_INSTANT =
            DateTimeFormatter.ofPattern("MMM d, uuuu 'at' HH:mm 'UTC'", Locale.ENGLISH)
                    .withZone(ZoneOffset.UTC);

    public ComposedEmail compose(JsonNode event) {
        String type = textOrNull(event, "type");
        if (type == null) {
            throw new IllegalArgumentException("Event payload has no 'type' field");
        }
        String recipient = textOrNull(event, "userEmail");
        if (recipient == null) {
            throw new IllegalArgumentException("Event payload has no 'userEmail' field");
        }
        String eventName = event.path("eventName").asText("your event");
        String seatId = event.path("seatId").asText("");
        UUID eventId = uuidOrNull(event, "eventId");

        return switch (type) {
            case TYPE_BOOKING_CONFIRMED -> new ComposedEmail(type, recipient,
                    "Booking confirmed — %s, seat %s".formatted(eventName, seatId),
                    """
                    Hi,

                    Your seat %s for %s is confirmed%s.

                    Booking reference: %s

                    Thanks for booking with SeatSync. See you there!
                    """.formatted(seatId, eventName, priceSuffix(event),
                            event.path("bookingId").asText("n/a")),
                    eventId, seatId);
            case TYPE_HOLD_EXPIRED -> new ComposedEmail(type, recipient,
                    "Seat hold expired — %s, seat %s".formatted(eventName, seatId),
                    """
                    Hi,

                    Your hold on seat %s for %s has expired and the seat has been released.

                    If you still want to attend, head back to SeatSync and pick a seat — it may still be available.
                    """.formatted(seatId, eventName),
                    eventId, seatId);
            case TYPE_WAITLIST_OFFERED -> new ComposedEmail(type, recipient,
                    "A seat just opened up — %s, seat %s".formatted(eventName, seatId),
                    """
                    Hi,

                    Good news! Seat %s for %s has become available and is being offered to you from the waitlist.

                    This offer expires at %s. Book soon to secure your seat.
                    """.formatted(seatId, eventName, humanInstant(event.path("offerExpiresAt").asText(""))),
                    eventId, seatId);
            default -> throw new IllegalArgumentException("Unknown event type: " + type);
        };
    }

    private static String priceSuffix(JsonNode event) {
        JsonNode price = event.path("price");
        if (!price.isNumber()) {
            return "";
        }
        BigDecimal amount = price.decimalValue().setScale(2, RoundingMode.HALF_UP);
        return " — $" + amount.toPlainString();
    }

    private static String humanInstant(String iso) {
        try {
            return HUMAN_INSTANT.format(Instant.parse(iso));
        } catch (Exception e) {
            return iso.isBlank() ? "soon" : iso;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private static UUID uuidOrNull(JsonNode node, String field) {
        try {
            return UUID.fromString(node.path(field).asText(""));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
