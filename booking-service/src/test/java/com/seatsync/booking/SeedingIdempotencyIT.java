package com.seatsync.booking;

import com.seatsync.booking.service.InventoryService;
import com.seatsync.booking.support.AbstractBookingIT;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Idempotent inventory seeding (review #7): the Redisson lock is a stampede
 * damper ONLY. Here the lock is mocked to be permanently unavailable (as after a lease
 * expiry / GC pause / Redis failover), so BOTH seeders run the seeding path
 * concurrently — per-row ON CONFLICT DO NOTHING must make them converge on the
 * exact seat count with no exception, instead of the old 500 on the unique
 * constraint.
 */
class SeedingIdempotencyIT extends AbstractBookingIT {

    private static final int SEATS = 25;

    @MockitoBean
    private RedissonClient redissonClient;

    @Autowired
    private InventoryService inventoryService;

    @Test
    void twoConcurrentSeedersWithoutTheLockConvergeWithoutErrors() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(catalogGateway.getEvent(eventId)).thenReturn(publishedEvent(eventId, "Seed Storm"));
        when(catalogGateway.getSeatMap(eventId)).thenReturn(seatMapOf(eventId, SEATS, new BigDecimal("20.00")));

        // The "lock" can never be acquired — both seeders must proceed anyway.
        RLock deadLock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(deadLock);
        when(deadLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        Queue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    inventoryService.ensureSeeded(eventId);
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

        assertThat(errors).as("concurrent seeding must converge, never error").isEmpty();
        Integer count = jdbc.queryForObject(
                "select count(*) from seat_inventory where event_id = ?", Integer.class, eventId);
        assertThat(count).as("exact seat count, no duplicates").isEqualTo(SEATS);
        Integer distinct = jdbc.queryForObject(
                "select count(distinct seat_id) from seat_inventory where event_id = ?", Integer.class, eventId);
        assertThat(distinct).isEqualTo(SEATS);
    }
}
