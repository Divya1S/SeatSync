package com.seatsync.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailComposerTest {

    private final EmailComposer composer = new EmailComposer();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String EVENT_ID = "8d7c2c6e-3f1a-4a71-9a3b-111111111111";

    @Test
    void composesBookingConfirmedEmail() throws JsonProcessingException {
        JsonNode event = parse("""
                {"type":"BookingConfirmed","bookingId":"b6f0a7f4-0000-0000-0000-222222222222",
                 "eventId":"%s","eventName":"Friday Night Jazz","seatId":"A-1-4",
                 "userId":"u-1","userEmail":"attendee@seatsync.local","price":49.00,
                 "occurredAt":"2026-07-16T18:00:00Z"}
                """.formatted(EVENT_ID));

        ComposedEmail email = composer.compose(event);

        assertThat(email.type()).isEqualTo("BookingConfirmed");
        assertThat(email.recipient()).isEqualTo("attendee@seatsync.local");
        assertThat(email.subject()).isEqualTo("Booking confirmed — Friday Night Jazz, seat A-1-4");
        assertThat(email.body())
                .contains("Your seat A-1-4 for Friday Night Jazz is confirmed — $49.00.")
                .contains("Booking reference: b6f0a7f4-0000-0000-0000-222222222222");
        assertThat(email.eventId()).isEqualTo(UUID.fromString(EVENT_ID));
        assertThat(email.seatId()).isEqualTo("A-1-4");
    }

    @Test
    void formatsPriceWithTwoDecimals() throws JsonProcessingException {
        JsonNode event = parse("""
                {"type":"BookingConfirmed","bookingId":"b1","eventId":"%s",
                 "eventName":"Friday Night Jazz","seatId":"B-2-7",
                 "userEmail":"a@b.c","price":25.5}
                """.formatted(EVENT_ID));

        assertThat(composer.compose(event).body()).contains("— $25.50.");
    }

    @Test
    void composesHoldExpiredEmail() throws JsonProcessingException {
        JsonNode event = parse("""
                {"type":"HoldExpired","holdId":"h-1","eventId":"%s",
                 "eventName":"Friday Night Jazz","seatId":"A-2-9","userId":"u-1",
                 "userEmail":"attendee@seatsync.local","occurredAt":"2026-07-16T18:05:00Z"}
                """.formatted(EVENT_ID));

        ComposedEmail email = composer.compose(event);

        assertThat(email.subject()).isEqualTo("Seat hold expired — Friday Night Jazz, seat A-2-9");
        assertThat(email.body())
                .contains("Your hold on seat A-2-9 for Friday Night Jazz has expired")
                .contains("the seat has been released");
    }

    @Test
    void composesWaitlistOfferedEmail() throws JsonProcessingException {
        JsonNode event = parse("""
                {"type":"WaitlistOffered","eventId":"%s","eventName":"Friday Night Jazz",
                 "seatId":"A-1-4","userId":"u-2","userEmail":"waiting@seatsync.local",
                 "offerExpiresAt":"2026-07-16T18:10:00Z","occurredAt":"2026-07-16T18:00:00Z"}
                """.formatted(EVENT_ID));

        ComposedEmail email = composer.compose(event);

        assertThat(email.subject()).isEqualTo("A seat just opened up — Friday Night Jazz, seat A-1-4");
        assertThat(email.body())
                .contains("Seat A-1-4 for Friday Night Jazz has become available")
                .contains("This offer expires at Jul 16, 2026 at 18:10 UTC.");
    }

    @Test
    void rejectsUnknownType() throws JsonProcessingException {
        JsonNode event = parse("""
                {"type":"SomethingElse","userEmail":"a@b.c"}
                """);

        assertThatThrownBy(() -> composer.compose(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown event type");
    }

    @Test
    void rejectsMissingTypeOrRecipient() throws JsonProcessingException {
        JsonNode noType = parse("""
                {"userEmail":"a@b.c"}
                """);
        JsonNode noRecipient = parse("""
                {"type":"BookingConfirmed","eventName":"X","seatId":"A-1-1"}
                """);

        assertThatThrownBy(() -> composer.compose(noType)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> composer.compose(noRecipient)).isInstanceOf(IllegalArgumentException.class);
    }

    private JsonNode parse(String json) throws JsonProcessingException {
        return objectMapper.readTree(json);
    }
}
