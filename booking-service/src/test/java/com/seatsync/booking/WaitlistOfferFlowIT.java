package com.seatsync.booking;

import com.seatsync.booking.service.HoldExpiryService;
import com.seatsync.booking.support.AbstractBookingIT;
import com.seatsync.booking.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

/**
 * Waitlist offer-holds end to end (review #2): expiry of a held seat with a
 * waitlisted user creates a REAL 10-minute hold owned by that user, writes
 * WaitlistOffered (incl. that holdId) to the outbox and removes the entry; the
 * offeree confirms through the normal POST /api/bookings. An ignored offer-hold
 * expires via the standard sweeper and cascades to the next entry.
 */
class WaitlistOfferFlowIT extends AbstractBookingIT {

    @Autowired
    private HoldExpiryService holdExpiryService;

    @Test
    void expiryOffersARealTenMinuteHoldToTheOldestWaitlisterWhoCanThenConfirm() {
        UUID eventId = UUID.randomUUID();
        when(catalogGateway.getEvent(eventId)).thenReturn(publishedEvent(eventId, "Waitlist Night"));
        when(catalogGateway.getSeatMap(eventId)).thenReturn(seatMapOf(eventId, 2, new BigDecimal("60.00")));

        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        String tokenA = TestTokens.attendee(userA, "a@test.io");
        String tokenB = TestTokens.attendee(userB, "b@test.io");

        // A holds the seat; B queues up.
        ResponseEntity<Map<String, Object>> holdA = postJson("/api/holds",
                Map.of("eventId", eventId.toString(), "seatId", "A-1-1"), tokenA);
        assertThat(holdA.getStatusCode().value()).isEqualTo(201);
        assertThat(postJson("/api/events/" + eventId + "/waitlist", Map.of(), tokenB)
                .getStatusCode().value()).isEqualTo(201);

        // A's hold expires; the sweep must offer the freed seat to B.
        jdbc.update("update holds set expires_at = now() - interval '1 second' where id = ?",
                UUID.fromString(String.valueOf(holdA.getBody().get("holdId"))));
        holdExpiryService.expireHolds();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<Map<String, Object>> offerHolds = jdbc.queryForList(
                    "select id from holds where event_id = ? and user_id = ? and status = 'HELD'",
                    eventId, userB);
            assertThat(offerHolds).as("offer must create a real hold owned by B").hasSize(1);
        });
        UUID offerHoldId = (UUID) jdbc.queryForList(
                "select id from holds where event_id = ? and user_id = ? and status = 'HELD'",
                eventId, userB).get(0).get("id");

        // Real 10-minute reservation, not a promise.
        Instant offerExpiresAt = jdbc.queryForObject(
                "select expires_at from holds where id = ?", OffsetDateTime.class, offerHoldId).toInstant();
        assertThat(offerExpiresAt)
                .isAfter(Instant.now().plus(Duration.ofMinutes(8)))
                .isBefore(Instant.now().plus(Duration.ofMinutes(12)));

        // Entry consumed atomically with the offer; the outbox row names the hold.
        Integer entries = jdbc.queryForObject(
                "select count(*) from waitlist_entries where event_id = ?", Integer.class, eventId);
        assertThat(entries).isZero();
        Integer offered = jdbc.queryForObject(
                "select count(*) from outbox_events where topic = 'seatsync.waitlist.offered' "
                        + "and message_key = ? and payload like ?",
                Integer.class, eventId.toString(), "%\"holdId\":\"" + offerHoldId + "\"%");
        assertThat(offered).as("WaitlistOffered must carry the offer-hold id").isEqualTo(1);

        // The offeree confirms through the NORMAL booking endpoint.
        ResponseEntity<Map<String, Object>> confirm = postJson("/api/bookings",
                Map.of("holdId", offerHoldId.toString()), tokenB);
        assertThat(confirm.getStatusCode().value()).isEqualTo(201);
        assertThat(confirm.getBody().get("status")).isEqualTo("CONFIRMED");
        String seatStatus = jdbc.queryForObject(
                "select status from seat_inventory where event_id = ? and seat_id = 'A-1-1'",
                String.class, eventId);
        assertThat(seatStatus).isEqualTo("BOOKED");
    }

    @Test
    void expiredOfferHoldCascadesToTheNextWaitlistEntryAndStopsWhenEmpty() {
        UUID eventId = UUID.randomUUID();
        when(catalogGateway.getEvent(eventId)).thenReturn(publishedEvent(eventId, "Cascade Night"));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("insert into seat_inventory "
                        + "(id, event_id, seat_id, section, row_label, seat_number, price, status, version) "
                        + "values (?, ?, 'A-1-1', 'A', '1', 1, ?, 'AVAILABLE', 0)",
                UUID.randomUUID(), eventId, new BigDecimal("60.00"));

        UUID userB = UUID.randomUUID();
        UUID userC = UUID.randomUUID();
        jdbc.update("insert into waitlist_entries (id, event_id, user_id, user_email, created_at) "
                        + "values (?, ?, ?, 'b@test.io', ?)",
                UUID.randomUUID(), eventId, userB, now.minusSeconds(100));
        jdbc.update("insert into waitlist_entries (id, event_id, user_id, user_email, created_at) "
                        + "values (?, ?, ?, 'c@test.io', ?)",
                UUID.randomUUID(), eventId, userC, now.minusSeconds(50));

        // An already-expired hold by some user A frees the seat on the next sweep.
        jdbc.update("insert into holds "
                        + "(id, event_id, seat_id, user_id, user_email, status, price, expires_at, created_at) "
                        + "values (?, ?, 'A-1-1', ?, 'a@test.io', 'HELD', ?, ?, ?)",
                UUID.randomUUID(), eventId, UUID.randomUUID(),
                new BigDecimal("60.00"), now.minusSeconds(30), now.minusSeconds(330));

        holdExpiryService.expireHolds();

        // B (older entry) gets the first offer.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<Map<String, Object>> offerHolds = jdbc.queryForList(
                    "select id from holds where event_id = ? and user_id = ? and status = 'HELD'",
                    eventId, userB);
            assertThat(offerHolds).hasSize(1);
        });
        UUID offerHoldB = (UUID) jdbc.queryForList(
                "select id from holds where event_id = ? and user_id = ? and status = 'HELD'",
                eventId, userB).get(0).get("id");
        assertThat(jdbc.queryForObject("select count(*) from waitlist_entries where event_id = ?",
                Integer.class, eventId)).isEqualTo(1);

        // B ignores the offer: the offer-hold expires; the sweep cascades to C.
        jdbc.update("update holds set expires_at = now() - interval '1 second' where id = ?", offerHoldB);
        holdExpiryService.expireHolds();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<Map<String, Object>> offerHolds = jdbc.queryForList(
                    "select id from holds where event_id = ? and user_id = ? and status = 'HELD'",
                    eventId, userC);
            assertThat(offerHolds).as("expired offer must cascade to the next entry").hasSize(1);
            String bStatus = jdbc.queryForObject(
                    "select status from holds where id = ?", String.class, offerHoldB);
            assertThat(bStatus).isEqualTo("EXPIRED");
        });

        // Waitlist drained; cascade stops here (no further offers, no loop).
        assertThat(jdbc.queryForObject("select count(*) from waitlist_entries where event_id = ?",
                Integer.class, eventId)).isZero();
        Integer offersTotal = jdbc.queryForObject(
                "select count(*) from outbox_events where topic = 'seatsync.waitlist.offered' and message_key = ?",
                Integer.class, eventId.toString());
        assertThat(offersTotal).as("exactly one offer per consumed entry").isEqualTo(2);
    }
}
