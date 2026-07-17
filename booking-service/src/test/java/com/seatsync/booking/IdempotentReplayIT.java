package com.seatsync.booking;

import com.seatsync.booking.support.AbstractBookingIT;
import com.seatsync.booking.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Idempotent replay semantics (review #4): {@code bookings.hold_id} is UNIQUE,
 * so holdId is the natural idempotency key — a client retrying after a lost
 * response gets its original result (200), never a scary 409; rivals keep
 * their 403/409.
 */
class IdempotentReplayIT extends AbstractBookingIT {

    @Test
    void confirmingTheSameHoldTwiceYields201Then200WithTheSameSingleBooking() {
        UUID eventId = seededEvent("Replay Night");
        String token = TestTokens.attendee(UUID.randomUUID(), "replayer@test.io");

        ResponseEntity<Map<String, Object>> hold = postJson("/api/holds",
                Map.of("eventId", eventId.toString(), "seatId", "A-1-1"), token);
        assertThat(hold.getStatusCode().value()).isEqualTo(201);
        String holdId = String.valueOf(hold.getBody().get("holdId"));

        ResponseEntity<Map<String, Object>> first = postJson("/api/bookings", Map.of("holdId", holdId), token);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        String bookingId = String.valueOf(first.getBody().get("bookingId"));

        ResponseEntity<Map<String, Object>> replay = postJson("/api/bookings", Map.of("holdId", holdId), token);
        assertThat(replay.getStatusCode().value())
                .as("a retried confirm must replay with 200, not conflict").isEqualTo(200);
        assertThat(String.valueOf(replay.getBody().get("bookingId")))
                .as("replay must return the ORIGINAL booking").isEqualTo(bookingId);
        assertThat(replay.getBody().get("status")).isEqualTo("CONFIRMED");

        Integer bookingsForHold = jdbc.queryForObject(
                "select count(*) from bookings where hold_id = ?", Integer.class, UUID.fromString(holdId));
        assertThat(bookingsForHold).as("exactly one booking row per hold").isEqualTo(1);
    }

    @Test
    void rivalConfirmOfAConfirmedHoldStillGets403() {
        UUID eventId = seededEvent("Rival Night");
        String owner = TestTokens.attendee(UUID.randomUUID(), "owner@test.io");
        String rival = TestTokens.attendee(UUID.randomUUID(), "rival@test.io");

        ResponseEntity<Map<String, Object>> hold = postJson("/api/holds",
                Map.of("eventId", eventId.toString(), "seatId", "A-1-1"), owner);
        String holdId = String.valueOf(hold.getBody().get("holdId"));
        assertThat(postJson("/api/bookings", Map.of("holdId", holdId), owner).getStatusCode().value())
                .isEqualTo(201);

        ResponseEntity<Map<String, Object>> rivalReplay =
                postJson("/api/bookings", Map.of("holdId", holdId), rival);
        assertThat(rivalReplay.getStatusCode().value())
                .as("someone else's holdId must never replay their booking").isEqualTo(403);
    }

    @Test
    void requestingASeatYouAlreadyHoldYields201Then200WithTheSameHold() {
        UUID eventId = seededEvent("Hold Replay Night");
        String token = TestTokens.attendee(UUID.randomUUID(), "holder@test.io");

        ResponseEntity<Map<String, Object>> first = postJson("/api/holds",
                Map.of("eventId", eventId.toString(), "seatId", "A-1-2"), token);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        String holdId = String.valueOf(first.getBody().get("holdId"));

        ResponseEntity<Map<String, Object>> replay = postJson("/api/holds",
                Map.of("eventId", eventId.toString(), "seatId", "A-1-2"), token);
        assertThat(replay.getStatusCode().value())
                .as("re-requesting your own held seat must replay with 200").isEqualTo(200);
        assertThat(String.valueOf(replay.getBody().get("holdId")))
                .as("replay must return the EXISTING hold").isEqualTo(holdId);
        assertThat(replay.getBody().get("status")).isEqualTo("HELD");

        Integer activeHolds = jdbc.queryForObject(
                "select count(*) from holds where event_id = ? and seat_id = 'A-1-2' and status = 'HELD'",
                Integer.class, eventId);
        assertThat(activeHolds).isEqualTo(1);
    }

    @Test
    void rivalHoldOnAHeldSeatStillGets409() {
        UUID eventId = seededEvent("Rival Hold Night");
        String holder = TestTokens.attendee(UUID.randomUUID(), "holder@test.io");
        String rival = TestTokens.attendee(UUID.randomUUID(), "rival@test.io");

        assertThat(postJson("/api/holds",
                Map.of("eventId", eventId.toString(), "seatId", "A-1-1"), holder)
                .getStatusCode().value()).isEqualTo(201);

        ResponseEntity<Map<String, Object>> rivalHold = postJson("/api/holds",
                Map.of("eventId", eventId.toString(), "seatId", "A-1-1"), rival);
        assertThat(rivalHold.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void confirmingAnExpiredHoldStillGets409() {
        UUID eventId = seededEvent("Expired Night");
        String token = TestTokens.attendee(UUID.randomUUID(), "late@test.io");

        ResponseEntity<Map<String, Object>> hold = postJson("/api/holds",
                Map.of("eventId", eventId.toString(), "seatId", "A-1-1"), token);
        String holdId = String.valueOf(hold.getBody().get("holdId"));
        jdbc.update("update holds set expires_at = now() - interval '1 minute' where id = ?",
                UUID.fromString(holdId));

        // 409 whether the sweeper already flipped it to EXPIRED or the confirm
        // hits the DB-timestamp expiry check first.
        ResponseEntity<Map<String, Object>> confirm = postJson("/api/bookings", Map.of("holdId", holdId), token);
        assertThat(confirm.getStatusCode().value()).isEqualTo(409);
    }

    private UUID seededEvent(String name) {
        UUID eventId = UUID.randomUUID();
        when(catalogGateway.getEvent(eventId)).thenReturn(publishedEvent(eventId, name));
        when(catalogGateway.getSeatMap(eventId)).thenReturn(seatMapOf(eventId, 3, new BigDecimal("35.00")));
        return eventId;
    }
}
