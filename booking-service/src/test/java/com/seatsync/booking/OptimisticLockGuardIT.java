package com.seatsync.booking;

import com.seatsync.booking.support.AbstractBookingIT;
import com.seatsync.booking.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import static org.mockito.Mockito.when;

/**
 * Proves the JPA @Version optimistic lock is the FINAL no-oversell guard even when
 * Redis hold exclusivity is completely bypassed: 60 valid HELD rows for the SAME
 * seat are inserted straight into the database (no Redis keys), then 60 threads
 * confirm concurrently. Exactly one may win the AVAILABLE -> BOOKED transition.
 */
class OptimisticLockGuardIT extends AbstractBookingIT {

    private static final int THREADS = 60;

    @Test
    void sixtyValidHoldsOnSameSeatYieldExactlyOneBooking() throws Exception {
        UUID eventId = UUID.randomUUID();
        String seatId = "A-1-1";
        when(catalogGateway.getEvent(eventId)).thenReturn(publishedEvent(eventId, "Lock Night"));

        jdbc.update("insert into seat_inventory "
                        + "(id, event_id, seat_id, section, row_label, seat_number, price, status, version) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'AVAILABLE', 0)",
                UUID.randomUUID(), eventId, seatId, "A", "1", 1, new BigDecimal("55.00"));

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        record HoldFixture(UUID holdId, String token) {
        }
        List<HoldFixture> holds = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            UUID holdId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            jdbc.update("insert into holds "
                            + "(id, event_id, seat_id, user_id, user_email, status, price, expires_at, created_at) "
                            + "values (?, ?, ?, ?, ?, 'HELD', ?, ?, ?)",
                    holdId, eventId, seatId, userId, "locker" + i + "@test.io",
                    new BigDecimal("55.00"), now.plusMinutes(5), now);
            holds.add(new HoldFixture(holdId, TestTokens.attendee(userId, "locker" + i + "@test.io")));
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        Queue<Integer> statuses = new ConcurrentLinkedQueue<>();
        Queue<Throwable> unexpectedErrors = new ConcurrentLinkedQueue<>();

        for (HoldFixture fixture : holds) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await(30, TimeUnit.SECONDS);
                    ResponseEntity<Map<String, Object>> confirm = postJson("/api/bookings",
                            Map.of("holdId", fixture.holdId().toString()), fixture.token());
                    statuses.add(confirm.getStatusCode().value());
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(unexpectedErrors).isEmpty();
        assertThat(statuses).hasSize(THREADS);
        assertThat(statuses).allMatch(s -> s == 201 || s == 409, "confirm must be 201 or a clean 409, never 5xx");
        assertThat(statuses.stream().filter(s -> s == 201).count())
                .as("exactly one confirm may win").isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(THREADS - 1);

        Integer confirmedBookings = jdbc.queryForObject(
                "select count(*) from bookings where event_id = ? and status = 'CONFIRMED'",
                Integer.class, eventId);
        assertThat(confirmedBookings).isEqualTo(1);

        Map<String, Object> seat = jdbc.queryForMap(
                "select status, version from seat_inventory where event_id = ? and seat_id = ?",
                eventId, seatId);
        assertThat(seat.get("status")).isEqualTo("BOOKED");
        assertThat(((Number) seat.get("version")).longValue()).isEqualTo(1L);
    }
}
