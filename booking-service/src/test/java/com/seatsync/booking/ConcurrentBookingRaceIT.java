package com.seatsync.booking;

import com.seatsync.booking.support.AbstractBookingIT;
import com.seatsync.booking.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
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
 * The core no-oversell guarantee: 60 concurrent buyers race for 10 seats.
 * Each thread picks a random seat, POSTs a hold and immediately POSTs the booking
 * confirm, over real HTTP with real JWTs against real Postgres/Redis/Kafka.
 *
 * Layered guards under test: Redis SET NX PX hold claim (first line) and the
 * JPA @Version optimistic lock on seat_inventory (final line).
 */
class ConcurrentBookingRaceIT extends AbstractBookingIT {

    private static final int SEATS = 10;
    private static final int THREADS = 60;

    @Test
    void sixtyConcurrentBuyersCannotOversellTenSeats() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(catalogGateway.getEvent(eventId)).thenReturn(publishedEvent(eventId, "Race Night"));
        when(catalogGateway.getSeatMap(eventId)).thenReturn(seatMapOf(eventId, SEATS, new BigDecimal("49.00")));

        List<String> seatIds = new ArrayList<>();
        for (int n = 1; n <= SEATS; n++) {
            seatIds.add("A-1-" + n);
        }

        // Random seat per thread, with every seat drawn at least once so the
        // expected outcome (all 10 seats sold) is deterministic.
        Random random = new Random(42);
        List<String> picks = new ArrayList<>(seatIds);
        for (int i = SEATS; i < THREADS; i++) {
            picks.add(seatIds.get(random.nextInt(SEATS)));
        }
        Collections.shuffle(picks, random);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        Queue<Integer> holdStatuses = new ConcurrentLinkedQueue<>();
        Queue<Integer> confirmStatuses = new ConcurrentLinkedQueue<>();
        Queue<Throwable> unexpectedErrors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREADS; i++) {
            final String seatId = picks.get(i);
            final String token = TestTokens.attendee(UUID.randomUUID(), "buyer" + i + "@test.io");
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await(30, TimeUnit.SECONDS);
                    ResponseEntity<Map<String, Object>> hold = postJson("/api/holds",
                            Map.of("eventId", eventId.toString(), "seatId", seatId), token);
                    holdStatuses.add(hold.getStatusCode().value());
                    if (hold.getStatusCode().value() == 201) {
                        String holdId = String.valueOf(hold.getBody().get("holdId"));
                        ResponseEntity<Map<String, Object>> confirm = postJson("/api/bookings",
                                Map.of("holdId", holdId), token);
                        confirmStatuses.add(confirm.getStatusCode().value());
                    }
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        start.countDown(); // single release: all 60 threads fire together
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(unexpectedErrors).isEmpty();

        // HTTP level: exactly one hold per seat wins, everyone else gets a clean 409, never a 5xx.
        assertThat(holdStatuses).hasSize(THREADS);
        assertThat(holdStatuses).allMatch(s -> s == 201 || s == 409, "hold responses must be 201 or 409");
        assertThat(holdStatuses.stream().filter(s -> s == 201).count()).isEqualTo(SEATS);
        assertThat(holdStatuses.stream().filter(s -> s == 409).count()).isEqualTo(THREADS - SEATS);
        assertThat(confirmStatuses).hasSize(SEATS);
        assertThat(confirmStatuses).allMatch(s -> s == 201, "every valid hold must confirm exactly once");

        // Database level: zero oversell.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer confirmedBookings = jdbc.queryForObject(
                    "select count(*) from bookings where event_id = ? and status = 'CONFIRMED'",
                    Integer.class, eventId);
            assertThat(confirmedBookings).isEqualTo(SEATS);

            Integer bookedSeats = jdbc.queryForObject(
                    "select count(*) from seat_inventory where event_id = ? and status = 'BOOKED'",
                    Integer.class, eventId);
            assertThat(bookedSeats).isEqualTo(SEATS);

            Integer availableSeats = jdbc.queryForObject(
                    "select count(*) from seat_inventory where event_id = ? and status = 'AVAILABLE'",
                    Integer.class, eventId);
            assertThat(availableSeats).isZero();

            List<Map<String, Object>> doubleBooked = jdbc.queryForList(
                    "select seat_id, count(*) as c from bookings "
                            + "where event_id = ? and status = 'CONFIRMED' "
                            + "group by seat_id having count(*) > 1",
                    eventId);
            assertThat(doubleBooked).as("no seat may have more than one CONFIRMED booking").isEmpty();
        });
    }
}
