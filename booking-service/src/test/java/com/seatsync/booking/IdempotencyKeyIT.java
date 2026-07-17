package com.seatsync.booking;

import com.seatsync.booking.support.AbstractBookingIT;
import com.seatsync.booking.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

/**
 * Client-supplied Idempotency-Key semantics over real HTTP (conventions 6.3):
 * replay of the stored status+body (marked {@code Idempotency-Replayed: true}),
 * 422 on key reuse with a different body, per-user scoping, 4xx outcomes stored
 * even though the domain transaction rolled back, and no double side effects
 * under concurrent duplicates.
 */
class IdempotencyKeyIT extends AbstractBookingIT {

    private static final String REPLAYED = "Idempotency-Replayed";

    @Test
    void sameKeySameBodyOnHoldsReplaysTheStored201AndCreatesExactlyOneHold() {
        UUID eventId = seededEvent("Idem Hold Night");
        String token = TestTokens.attendee(UUID.randomUUID(), "idem-hold@test.io");
        String key = "idem-" + UUID.randomUUID();
        String body = holdBody(eventId, "A-1-1");

        ResponseEntity<Map<String, Object>> first = post("/api/holds", body, token, key);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(first.getHeaders().getFirst(REPLAYED)).isNull();

        ResponseEntity<Map<String, Object>> second = post("/api/holds", body, token, key);
        assertThat(second.getStatusCode().value())
                .as("replay must return the stored status (201), not the natural-key 200").isEqualTo(201);
        assertThat(second.getHeaders().getFirst(REPLAYED)).isEqualTo("true");
        assertThat(second.getBody()).as("replayed body must be identical").isEqualTo(first.getBody());

        Integer holds = jdbc.queryForObject(
                "select count(*) from holds where event_id = ? and seat_id = 'A-1-1'", Integer.class, eventId);
        assertThat(holds).as("the replay must not create a second hold").isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejectedWith422() {
        UUID eventId = seededEvent("Idem Mismatch Night");
        String token = TestTokens.attendee(UUID.randomUUID(), "idem-mismatch@test.io");
        String key = "idem-" + UUID.randomUUID();

        assertThat(post("/api/holds", holdBody(eventId, "A-1-1"), token, key).getStatusCode().value())
                .isEqualTo(201);

        ResponseEntity<Map<String, Object>> mismatch =
                post("/api/holds", holdBody(eventId, "A-1-2"), token, key);
        assertThat(mismatch.getStatusCode().value()).isEqualTo(422);
        assertThat(String.valueOf(mismatch.getBody().get("detail"))).contains("different request body");

        Integer holdsOnOtherSeat = jdbc.queryForObject(
                "select count(*) from holds where event_id = ? and seat_id = 'A-1-2'", Integer.class, eventId);
        assertThat(holdsOnOtherSeat).as("the mismatching request must not execute").isZero();
    }

    @Test
    void bookingReplayComesFromTheStoreEvenAfterTheHoldStateChanged() {
        UUID eventId = seededEvent("Idem Booking Night");
        String token = TestTokens.attendee(UUID.randomUUID(), "idem-booking@test.io");

        ResponseEntity<Map<String, Object>> hold =
                post("/api/holds", holdBody(eventId, "A-1-1"), token, null);
        assertThat(hold.getStatusCode().value()).isEqualTo(201);
        String holdId = String.valueOf(hold.getBody().get("holdId"));

        String key = "idem-" + UUID.randomUUID();
        String body = bookingBody(holdId);
        ResponseEntity<Map<String, Object>> first = post("/api/bookings", body, token, key);
        assertThat(first.getStatusCode().value()).isEqualTo(201);

        // Mutate the hold AFTER the confirm: any re-execution would now 409, and
        // even the natural-key replay would answer 200 — only the idempotency
        // store can answer 201 with the original body.
        jdbc.update("update holds set status = 'EXPIRED' where id = ?", UUID.fromString(holdId));

        ResponseEntity<Map<String, Object>> replay = post("/api/bookings", body, token, key);
        assertThat(replay.getStatusCode().value())
                .as("replay must come from the store, not re-execution").isEqualTo(201);
        assertThat(replay.getHeaders().getFirst(REPLAYED)).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(first.getBody());

        Integer bookings = jdbc.queryForObject(
                "select count(*) from bookings where hold_id = ?", Integer.class, UUID.fromString(holdId));
        assertThat(bookings).as("exactly one booking row").isEqualTo(1);
    }

    @Test
    void sameKeyStringIsIndependentPerUser() {
        UUID eventId = seededEvent("Idem Scope Night");
        String userA = TestTokens.attendee(UUID.randomUUID(), "scope-a@test.io");
        String userB = TestTokens.attendee(UUID.randomUUID(), "scope-b@test.io");
        String sharedKey = "shared-key-" + UUID.randomUUID();

        ResponseEntity<Map<String, Object>> a =
                post("/api/holds", holdBody(eventId, "A-1-1"), userA, sharedKey);
        ResponseEntity<Map<String, Object>> b =
                post("/api/holds", holdBody(eventId, "A-1-2"), userB, sharedKey);

        assertThat(a.getStatusCode().value()).isEqualTo(201);
        assertThat(b.getStatusCode().value())
                .as("another user's identical key must process normally").isEqualTo(201);
        assertThat(a.getHeaders().getFirst(REPLAYED)).isNull();
        assertThat(b.getHeaders().getFirst(REPLAYED)).isNull();

        Integer holds = jdbc.queryForObject(
                "select count(*) from holds where event_id = ?", Integer.class, eventId);
        assertThat(holds).isEqualTo(2);
    }

    @Test
    void aConflict409FromARolledBackConfirmTransactionIsStoredAndReplays() {
        // Bypass Redis exclusivity (as OptimisticLockGuardIT does): seed the seat
        // plus two valid HELD rows directly, so the second confirm hits the seat
        // conflict INSIDE the transactional confirm and that transaction rolls back.
        UUID eventId = UUID.randomUUID();
        String seatId = "A-1-1";
        when(catalogGateway.getEvent(eventId)).thenReturn(publishedEvent(eventId, "Rollback Night"));
        jdbc.update("insert into seat_inventory "
                        + "(id, event_id, seat_id, section, row_label, seat_number, price, status, version) "
                        + "values (?, ?, ?, 'A', '1', 1, ?, 'AVAILABLE', 0)",
                UUID.randomUUID(), eventId, seatId, new BigDecimal("55.00"));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID winnerHold = UUID.randomUUID();
        UUID loserHold = UUID.randomUUID();
        UUID winnerId = UUID.randomUUID();
        UUID loserId = UUID.randomUUID();
        for (var fixture : List.of(Map.entry(winnerHold, winnerId), Map.entry(loserHold, loserId))) {
            jdbc.update("insert into holds "
                            + "(id, event_id, seat_id, user_id, user_email, status, price, expires_at, created_at) "
                            + "values (?, ?, ?, ?, 'x@test.io', 'HELD', ?, ?, ?)",
                    fixture.getKey(), eventId, seatId, fixture.getValue(),
                    new BigDecimal("55.00"), now.plusMinutes(5), now);
        }
        String winnerToken = TestTokens.attendee(winnerId, "winner@test.io");
        String loserToken = TestTokens.attendee(loserId, "loser@test.io");

        assertThat(post("/api/bookings", bookingBody(winnerHold.toString()), winnerToken, null)
                .getStatusCode().value()).isEqualTo(201);

        String key = "idem-" + UUID.randomUUID();
        String body = bookingBody(loserHold.toString());
        ResponseEntity<Map<String, Object>> conflict = post("/api/bookings", body, loserToken, key);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);

        Integer storedStatus = jdbc.queryForObject(
                "select response_status from idempotency_keys where user_id = ? and idem_key = ?",
                Integer.class, loserId, key);
        assertThat(storedStatus)
                .as("the 409 must be recorded although the confirm transaction rolled back")
                .isEqualTo(409);

        ResponseEntity<Map<String, Object>> replay = post("/api/bookings", body, loserToken, key);
        assertThat(replay.getStatusCode().value()).isEqualTo(409);
        assertThat(replay.getHeaders().getFirst(REPLAYED)).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(conflict.getBody());

        Integer bookings = jdbc.queryForObject(
                "select count(*) from bookings where event_id = ?", Integer.class, eventId);
        assertThat(bookings).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyRequestsYieldOneHoldAndACleanDuplicateOutcome() throws Exception {
        UUID eventId = seededEvent("Idem Race Night");
        String token = TestTokens.attendee(UUID.randomUUID(), "idem-race@test.io");
        String key = "idem-" + UUID.randomUUID();
        String body = holdBody(eventId, "A-1-1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        Queue<ResponseEntity<Map<String, Object>>> responses = new ConcurrentLinkedQueue<>();
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await(30, TimeUnit.SECONDS);
                    responses.add(post("/api/holds", body, token, key));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(errors).isEmpty();
        assertThat(responses).hasSize(2);

        List<ResponseEntity<Map<String, Object>>> winners = responses.stream()
                .filter(r -> r.getStatusCode().value() == 201
                        && !"true".equals(r.getHeaders().getFirst(REPLAYED)))
                .toList();
        assertThat(winners).as("exactly one request may actually process").hasSize(1);

        ResponseEntity<Map<String, Object>> other = responses.stream()
                .filter(r -> r != winners.get(0)).findFirst().orElseThrow();
        int otherStatus = other.getStatusCode().value();
        if (otherStatus == 409) {
            assertThat(String.valueOf(other.getBody().get("detail")))
                    .as("the loser must see the in-flight conflict")
                    .contains("still in progress");
        } else {
            assertThat(otherStatus).as("a clean late loser replays the stored 201").isEqualTo(201);
            assertThat(other.getHeaders().getFirst(REPLAYED)).isEqualTo("true");
            assertThat(other.getBody()).isEqualTo(winners.get(0).getBody());
        }

        Integer holds = jdbc.queryForObject(
                "select count(*) from holds where event_id = ? and seat_id = 'A-1-1'", Integer.class, eventId);
        assertThat(holds).as("no double side effects").isEqualTo(1);
    }

    @Test
    void keyLengthIsValidatedTo1Through255Characters() {
        UUID eventId = seededEvent("Idem Validation Night");
        String token = TestTokens.attendee(UUID.randomUUID(), "idem-validation@test.io");

        ResponseEntity<Map<String, Object>> tooLong =
                post("/api/holds", holdBody(eventId, "A-1-1"), token, "k".repeat(256));
        assertThat(tooLong.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<Map<String, Object>> empty =
                post("/api/holds", holdBody(eventId, "A-1-1"), token, "");
        assertThat(empty.getStatusCode().value()).isEqualTo(400);

        // A 255-char key is the maximum and must work.
        ResponseEntity<Map<String, Object>> maxLength =
                post("/api/holds", holdBody(eventId, "A-1-1"), token, "k".repeat(255));
        assertThat(maxLength.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void sweeperPurgesIdempotencyRowsOlderThan24Hours() {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into idempotency_keys "
                        + "(id, user_id, endpoint, idem_key, request_hash, response_status, response_body, created_at) "
                        + "values (?, ?, '/api/holds', 'stale-key', 'deadbeef', 201, '{}', ?)",
                UUID.randomUUID(), userId, OffsetDateTime.ofInstant(
                        Instant.now().minus(Duration.ofHours(25)), ZoneOffset.UTC));

        // The scheduled sweep (every 10s) purges rows older than 24h.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Integer stale = jdbc.queryForObject(
                    "select count(*) from idempotency_keys where user_id = ?", Integer.class, userId);
            assertThat(stale).isZero();
        });
    }

    private UUID seededEvent(String name) {
        UUID eventId = UUID.randomUUID();
        when(catalogGateway.getEvent(eventId)).thenReturn(publishedEvent(eventId, name));
        when(catalogGateway.getSeatMap(eventId)).thenReturn(seatMapOf(eventId, 3, new BigDecimal("35.00")));
        return eventId;
    }

    private static String holdBody(UUID eventId, String seatId) {
        return "{\"eventId\":\"" + eventId + "\",\"seatId\":\"" + seatId + "\"}";
    }

    private static String bookingBody(String holdId) {
        return "{\"holdId\":\"" + holdId + "\"}";
    }

    /**
     * Sends the body as a RAW string so byte-identical replays are guaranteed
     * (the request hash is computed over the raw bytes).
     */
    private ResponseEntity<Map<String, Object>> post(String path, String rawJson, String token, String idemKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        if (idemKey != null) {
            headers.set("Idempotency-Key", idemKey);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(rawJson, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }
}
