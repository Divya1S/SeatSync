package com.seatsync.booking.service;

import com.seatsync.booking.domain.Hold;
import com.seatsync.booking.domain.HoldStatus;
import com.seatsync.booking.repo.HoldRepository;
import com.seatsync.booking.repo.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Sweeps expired holds every 10 seconds. The sweep CLAIMS expired holds
 * atomically (review #5): a single transaction locks the candidate rows with
 * {@code FOR UPDATE SKIP LOCKED} and flips them to EXPIRED, so N replicas
 * divide the work — no hold is ever processed twice, without any global lock
 * or leader election.
 *
 * <p>Per claimed hold: mark EXPIRED, write HoldExpired to the outbox (same
 * transaction), release the Redis key, broadcast the seat AVAILABLE, then offer
 * the seat to the oldest waitlist entry. Because a waitlist offer is itself a
 * real hold with a 10-minute TTL (review #2), an ignored offer expires here too
 * and the offer naturally cascades to the next entry; when the waitlist is
 * empty the cascade simply stops.
 *
 * <p>The same sweep also purges client idempotency records older than 24 hours
 * (conventions section 6.3) — their replay window has closed.
 */
@Service
public class HoldExpiryService {

    /** Client Idempotency-Key records are replayable for 24h, then purged. */
    static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryService.class);

    private final HoldRepository holdRepository;
    private final RedisHoldStore redisHoldStore;
    private final BookingEventPublisher eventPublisher;
    private final SeatEventBroadcaster broadcaster;
    private final EventNameResolver eventNameResolver;
    private final WaitlistService waitlistService;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public HoldExpiryService(HoldRepository holdRepository,
                             RedisHoldStore redisHoldStore,
                             BookingEventPublisher eventPublisher,
                             SeatEventBroadcaster broadcaster,
                             EventNameResolver eventNameResolver,
                             WaitlistService waitlistService,
                             IdempotencyKeyRepository idempotencyKeyRepository) {
        this.holdRepository = holdRepository;
        this.redisHoldStore = redisHoldStore;
        this.eventPublisher = eventPublisher;
        this.broadcaster = broadcaster;
        this.eventNameResolver = eventNameResolver;
        this.waitlistService = waitlistService;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    @Scheduled(fixedDelay = 10_000)
    @Transactional
    public void expireHolds() {
        Instant now = Instant.now();
        List<Hold> claimed = holdRepository.claimExpired(now);
        for (Hold hold : claimed) {
            try {
                expireOne(hold);
            } catch (Exception e) {
                log.warn("Failed to expire hold {}: {}", hold.getId(), e.getMessage());
            }
        }
        purgeExpiredIdempotencyKeys(now);
    }

    /** Piggybacked on the sweep cadence; a failure must never abort the expiry work. */
    private void purgeExpiredIdempotencyKeys(Instant now) {
        try {
            int purged = idempotencyKeyRepository.deleteByCreatedAtBefore(now.minus(IDEMPOTENCY_RETENTION));
            if (purged > 0) {
                log.info("Purged {} idempotency keys older than {} hours",
                        purged, IDEMPOTENCY_RETENTION.toHours());
            }
        } catch (RuntimeException e) {
            log.warn("Idempotency key purge failed: {}", e.getMessage());
        }
    }

    void expireOne(Hold hold) {
        hold.setStatus(HoldStatus.EXPIRED);
        holdRepository.save(hold);
        // Outbox first (same transaction as the claim): the event can never be
        // lost to a best-effort side effect failing below.
        eventPublisher.holdExpired(hold, eventNameResolver.resolve(hold.getEventId()));
        redisHoldStore.release(hold.getEventId(), hold.getSeatId());
        broadcaster.seatAvailable(hold.getEventId(), hold.getSeatId());
        waitlistService.offerSeat(hold.getEventId(), hold.getSeatId());
    }
}
