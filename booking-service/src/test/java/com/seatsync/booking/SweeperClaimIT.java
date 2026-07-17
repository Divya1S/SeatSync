package com.seatsync.booking;

import com.seatsync.booking.service.HoldExpiryService;
import com.seatsync.booking.support.AbstractBookingIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Atomic sweeper claim (review #5): two sweeps running concurrently (as two
 * replicas would) must divide the expired holds, never process one twice.
 * {@code FOR UPDATE SKIP LOCKED} + status flip in the claiming transaction is
 * the whole mechanism — no ShedLock, no leader. Exactly-once is proven by the
 * outbox: precisely ONE HoldExpired row per hold, no duplicates.
 */
class SweeperClaimIT extends AbstractBookingIT {

    private static final int HOLDS = 8;

    @Autowired
    private HoldExpiryService holdExpiryService;

    @Test
    void twoConcurrentSweepsProcessEachExpiredHoldExactlyOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<UUID> holdIds = new ArrayList<>();
        for (int i = 1; i <= HOLDS; i++) {
            String seatId = "A-1-" + i;
            jdbc.update("insert into seat_inventory "
                            + "(id, event_id, seat_id, section, row_label, seat_number, price, status, version) "
                            + "values (?, ?, ?, 'A', '1', ?, ?, 'AVAILABLE', 0)",
                    UUID.randomUUID(), eventId, seatId, i, new BigDecimal("25.00"));
            UUID holdId = UUID.randomUUID();
            jdbc.update("insert into holds "
                            + "(id, event_id, seat_id, user_id, user_email, status, price, expires_at, created_at) "
                            + "values (?, ?, ?, ?, ?, 'HELD', ?, ?, ?)",
                    holdId, eventId, seatId, UUID.randomUUID(), "sweep" + i + "@test.io",
                    new BigDecimal("25.00"), now.minusSeconds(60), now.minusSeconds(360));
            holdIds.add(holdId);
        }

        // Two "replicas" sweep simultaneously.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    holdExpiryService.expireHolds();
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

        // Every hold ends EXPIRED with exactly ONE HoldExpired outbox row — the
        // background scheduler may also have raced; the claim keeps it exactly-once.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            for (UUID holdId : holdIds) {
                String status = jdbc.queryForObject(
                        "select status from holds where id = ?", String.class, holdId);
                assertThat(status).isEqualTo("EXPIRED");
                Integer events = jdbc.queryForObject(
                        "select count(*) from outbox_events "
                                + "where topic = 'seatsync.hold.expired' and payload like ?",
                        Integer.class, "%\"holdId\":\"" + holdId + "\"%");
                assertThat(events)
                        .as("hold %s must produce exactly one HoldExpired event", holdId)
                        .isEqualTo(1);
            }
        });
    }
}
