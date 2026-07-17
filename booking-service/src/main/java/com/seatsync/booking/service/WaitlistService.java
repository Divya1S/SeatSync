package com.seatsync.booking.service;

import com.seatsync.booking.config.BookingProperties;
import com.seatsync.booking.domain.Hold;
import com.seatsync.booking.domain.HoldStatus;
import com.seatsync.booking.domain.SeatInventory;
import com.seatsync.booking.domain.SeatStatus;
import com.seatsync.booking.domain.WaitlistEntry;
import com.seatsync.booking.error.ApiException;
import com.seatsync.booking.repo.HoldRepository;
import com.seatsync.booking.repo.SeatInventoryRepository;
import com.seatsync.booking.repo.WaitlistEntryRepository;
import com.seatsync.booking.security.JwtUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Waitlist domain. A waitlist offer is a REAL hold (review #2): offering a
 * freed seat to the oldest entry creates a system hold owned by that user with
 * a {@code booking.offer-ttl} TTL (default 10 minutes) — Redis key plus a holds
 * row — so the seat is genuinely reserved while the offer stands. The offeree
 * confirms via the normal {@code POST /api/bookings {holdId}}; an ignored offer
 * expires through the standard sweeper, which cascades to the next entry.
 */
@Service
public class WaitlistService {

    private static final Logger log = LoggerFactory.getLogger(WaitlistService.class);

    private final WaitlistEntryRepository waitlistEntryRepository;
    private final HoldRepository holdRepository;
    private final SeatInventoryRepository seatInventoryRepository;
    private final RedisHoldStore redisHoldStore;
    private final BookingEventPublisher eventPublisher;
    private final EventNameResolver eventNameResolver;
    private final SeatEventBroadcaster broadcaster;
    private final BookingProperties bookingProperties;

    public WaitlistService(WaitlistEntryRepository waitlistEntryRepository,
                           HoldRepository holdRepository,
                           SeatInventoryRepository seatInventoryRepository,
                           RedisHoldStore redisHoldStore,
                           BookingEventPublisher eventPublisher,
                           EventNameResolver eventNameResolver,
                           SeatEventBroadcaster broadcaster,
                           BookingProperties bookingProperties) {
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.holdRepository = holdRepository;
        this.seatInventoryRepository = seatInventoryRepository;
        this.redisHoldStore = redisHoldStore;
        this.eventPublisher = eventPublisher;
        this.eventNameResolver = eventNameResolver;
        this.broadcaster = broadcaster;
        this.bookingProperties = bookingProperties;
    }

    public long join(UUID eventId, JwtUser user) {
        if (waitlistEntryRepository.existsByEventIdAndUserId(eventId, user.id())) {
            throw ApiException.conflict("Already on the waitlist for event " + eventId);
        }
        WaitlistEntry entry = new WaitlistEntry(eventId, user.id(), user.email(), Instant.now());
        try {
            entry = waitlistEntryRepository.save(entry);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("Already on the waitlist for event " + eventId);
        }
        return waitlistEntryRepository.countByEventIdAndCreatedAtLessThanEqual(eventId, entry.getCreatedAt());
    }

    @Transactional
    public void leave(UUID eventId, JwtUser user) {
        waitlistEntryRepository.deleteByEventIdAndUserId(eventId, user.id());
    }

    /**
     * Offers a freed seat to the oldest waitlist entry for the event (if any):
     * creates a 10-minute offer-hold owned by that user, writes WaitlistOffered
     * (incl. the holdId) to the outbox and removes the entry — all in the
     * caller's (sweeper/cancel) transaction. Redis claim and WS broadcast are
     * best-effort adjuncts deferred to after the commit.
     */
    @Transactional
    public void offerSeat(UUID eventId, String seatId) {
        Optional<WaitlistEntry> oldest = waitlistEntryRepository.findFirstByEventIdOrderByCreatedAtAsc(eventId);
        if (oldest.isEmpty()) {
            return; // cascade ends here — no infinite loop when the waitlist empties
        }
        SeatInventory seat = seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId).orElse(null);
        if (seat == null || seat.getStatus() != SeatStatus.AVAILABLE) {
            log.info("Seat {} of event {} is no longer available; keeping waitlist entries", seatId, eventId);
            return;
        }
        WaitlistEntry entry = oldest.get();
        UUID holdId = UUID.randomUUID();
        UUID userId = entry.getUserId();
        Instant now = Instant.now();
        // Redis PX and DB expires_at derive from the SAME configured TTL.
        Duration offerTtl = bookingProperties.offerTtl();
        Instant offerExpiresAt = now.plus(offerTtl);

        Hold offerHold = new Hold(holdId, eventId, seatId, userId, entry.getUserEmail(),
                HoldStatus.HELD, seat.getPrice(), offerExpiresAt, now);
        holdRepository.save(offerHold);
        eventPublisher.waitlistOffered(eventId, eventNameResolver.resolve(eventId), seatId,
                userId, entry.getUserEmail(), holdId, offerExpiresAt);
        waitlistEntryRepository.delete(entry);

        TxSideEffects.afterCommit(() -> {
            try {
                if (!redisHoldStore.tryClaim(eventId, seatId, holdId, userId, offerExpiresAt, offerTtl)) {
                    // DB is authoritative for confirm; a stale key only affects rival holds.
                    log.warn("Redis key for offer-hold {} on seat {} of event {} was already present",
                            holdId, seatId, eventId);
                }
            } catch (Exception e) {
                log.warn("Could not claim Redis key for offer-hold {}: {}", holdId, e.getMessage());
            }
            broadcaster.seatHeld(eventId, seatId);
        });

        log.info("Offered seat {} of event {} to waitlisted user {} via hold {} (expires {})",
                seatId, eventId, userId, holdId, offerExpiresAt);
    }
}
