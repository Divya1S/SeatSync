package com.seatsync.booking.service;

import com.seatsync.booking.domain.Hold;
import com.seatsync.booking.domain.HoldStatus;
import com.seatsync.booking.repo.HoldRepository;
import com.seatsync.booking.repo.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sweeps expired holds every 10 seconds. Each expired hold is claimed and
 * processed in its OWN transaction (review #5, hardened): the claim locks one
 * row with {@code FOR UPDATE SKIP LOCKED}, flips it to EXPIRED and writes the
 * HoldExpired outbox row in the same transaction, so N replicas divide the work
 * and the event can never be lost. A failure while processing one hold rolls
 * back only that hold; it is excluded for the rest of this run and retried on
 * the next sweep, so one bad row cannot stall expiry for everyone.
 *
 * <p>Best-effort side effects (Redis key release, WS broadcast) run strictly
 * AFTER the hold's transaction commits, never before, so clients are never told
 * a seat is free while its hold is still HELD. The waitlist offer joins the same
 * transaction; because an offer is itself a real hold with its own TTL, an
 * ignored offer expires here too and cascades to the next entry.
 *
 * <p>The same sweep also purges client idempotency records older than 24 hours
 * (conventions section 6.3).
 */
@Service
public class HoldExpiryService {

    /** Client Idempotency-Key records are replayable for 24h, then purged. */
    static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    /**
     * Upper bound per run (runs never overlap: fixedDelay). At roughly 5-10 ms per
     * hold this caps one run at about 10-20 s; a larger backlog continues on the
     * next run, 10 s later, so sustained throughput is ~200 holds/s per replica.
     */
    static final int MAX_HOLDS_PER_SWEEP = 2_000;

    /** Keeps the exclusion list non-empty so the native {@code NOT IN} is always valid. */
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryService.class);

    private final HoldRepository holdRepository;
    private final RedisHoldStore redisHoldStore;
    private final BookingEventPublisher eventPublisher;
    private final SeatEventBroadcaster broadcaster;
    private final EventNameResolver eventNameResolver;
    private final WaitlistService waitlistService;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final TransactionTemplate perHoldTransaction;

    public HoldExpiryService(HoldRepository holdRepository,
                             RedisHoldStore redisHoldStore,
                             BookingEventPublisher eventPublisher,
                             SeatEventBroadcaster broadcaster,
                             EventNameResolver eventNameResolver,
                             WaitlistService waitlistService,
                             IdempotencyKeyRepository idempotencyKeyRepository,
                             PlatformTransactionManager transactionManager) {
        this.holdRepository = holdRepository;
        this.redisHoldStore = redisHoldStore;
        this.eventPublisher = eventPublisher;
        this.broadcaster = broadcaster;
        this.eventNameResolver = eventNameResolver;
        this.waitlistService = waitlistService;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.perHoldTransaction = new TransactionTemplate(transactionManager);
        this.perHoldTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelay = 10_000)
    public void expireHolds() {
        Instant now = Instant.now();
        Set<UUID> skipped = new HashSet<>();
        skipped.add(NIL_UUID);
        int processed = 0;
        while (processed < MAX_HOLDS_PER_SWEEP) {
            AtomicReference<UUID> attempted = new AtomicReference<>();
            try {
                boolean found = Boolean.TRUE.equals(perHoldTransaction.execute(status -> {
                    Optional<Hold> claimed = holdRepository.claimOneExpired(now, skipped);
                    if (claimed.isEmpty()) {
                        return false;
                    }
                    Hold hold = claimed.get();
                    attempted.set(hold.getId());
                    expireOne(hold);
                    return true;
                }));
                if (!found) {
                    break;
                }
                processed++;
            } catch (RuntimeException e) {
                UUID failed = attempted.get();
                if (failed == null) {
                    // The claim itself failed (database trouble): stop this run.
                    log.warn("Hold-expiry sweep could not claim a hold: {}", e.getMessage());
                    break;
                }
                // Only this hold rolled back; step past it so the rest of the backlog
                // still drains, and let the next sweep retry it.
                skipped.add(failed);
                log.warn("Failed to expire hold {} (will retry next sweep): {}", failed, e.getMessage());
            }
        }
        purgeExpiredIdempotencyKeys(now);
    }

    /** Runs inside the per-hold transaction. */
    void expireOne(Hold hold) {
        hold.setStatus(HoldStatus.EXPIRED);
        holdRepository.save(hold);
        // Outbox in the same transaction as the status flip: the event can never
        // be lost to a best-effort side effect failing later.
        eventPublisher.holdExpired(hold, eventNameResolver.resolve(hold.getEventId()));
        UUID eventId = hold.getEventId();
        String seatId = hold.getSeatId();
        // Register the AVAILABLE broadcast BEFORE the offer: if the offer creates
        // a hold, its post-commit HELD broadcast must land after this one.
        TxSideEffects.afterCommit(() -> {
            safely(() -> redisHoldStore.release(eventId, seatId));
            safely(() -> broadcaster.seatAvailable(eventId, seatId));
        });
        waitlistService.offerSeat(eventId, seatId);
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

    private static void safely(Runnable sideEffect) {
        try {
            sideEffect.run();
        } catch (RuntimeException e) {
            log.warn("Post-commit side effect failed: {}", e.getMessage());
        }
    }
}
