package com.seatsync.booking.service;

import com.seatsync.booking.api.dto.HoldResponse;
import com.seatsync.booking.api.dto.HoldSummary;
import com.seatsync.booking.config.BookingProperties;
import com.seatsync.booking.domain.Hold;
import com.seatsync.booking.domain.HoldStatus;
import com.seatsync.booking.domain.SeatInventory;
import com.seatsync.booking.domain.SeatStatus;
import com.seatsync.booking.error.ApiException;
import com.seatsync.booking.repo.HoldRepository;
import com.seatsync.booking.repo.SeatInventoryRepository;
import com.seatsync.booking.security.JwtUser;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Hold flow, per conventions section 6.3:
 * 1. ensure inventory is seeded (catalog via circuit breaker; 503 when unavailable),
 * 2. seat must be AVAILABLE (else 409),
 * 3. idempotent replay: if the caller already owns the active hold on this seat,
 *    return it ({@code created=false} -> HTTP 200) instead of a self-inflicted 409,
 * 4. atomic Redis claim SET NX PX {@code booking.hold-ttl} (else 409),
 * 5. persist the hold row (expires_at = now + {@code booking.hold-ttl}) and
 *    broadcast HELD over STOMP (via the Redis fanout bridge).
 */
@Service
public class HoldService {

    /** {@code created} distinguishes a fresh hold (201) from an idempotent replay (200). */
    public record HoldResult(HoldResponse hold, boolean created) {
    }

    private final InventoryService inventoryService;
    private final SeatInventoryRepository seatInventoryRepository;
    private final HoldRepository holdRepository;
    private final RedisHoldStore redisHoldStore;
    private final SeatEventBroadcaster broadcaster;
    private final BookingProperties bookingProperties;

    public HoldService(InventoryService inventoryService,
                       SeatInventoryRepository seatInventoryRepository,
                       HoldRepository holdRepository,
                       RedisHoldStore redisHoldStore,
                       SeatEventBroadcaster broadcaster,
                       BookingProperties bookingProperties) {
        this.inventoryService = inventoryService;
        this.seatInventoryRepository = seatInventoryRepository;
        this.holdRepository = holdRepository;
        this.redisHoldStore = redisHoldStore;
        this.broadcaster = broadcaster;
        this.bookingProperties = bookingProperties;
    }

    public HoldResult createHold(UUID eventId, String seatId, JwtUser user) {
        inventoryService.ensureSeeded(eventId);

        SeatInventory seat = seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId)
                .orElseThrow(() -> ApiException.notFound("Seat " + seatId + " not found for event " + eventId));
        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw ApiException.conflict("Seat " + seatId + " is already booked");
        }

        Instant now = Instant.now();
        // Idempotent replay: re-requesting a seat you already hold returns the
        // existing hold (DB is authoritative, not the Redis TTL). A rival's
        // active hold is a fast 409 without touching Redis.
        Hold active = holdRepository
                .findFirstByEventIdAndSeatIdAndStatusAndExpiresAtAfter(eventId, seatId, HoldStatus.HELD, now)
                .orElse(null);
        if (active != null) {
            if (active.getUserId().equals(user.id())) {
                return new HoldResult(toResponse(active), false);
            }
            throw ApiException.conflict("Seat " + seatId + " is already held");
        }

        UUID holdId = UUID.randomUUID();
        // Redis PX and DB expires_at derive from the SAME configured TTL.
        Duration holdTtl = bookingProperties.holdTtl();
        Instant expiresAt = now.plus(holdTtl);
        if (!redisHoldStore.tryClaim(eventId, seatId, holdId, user.id(), expiresAt, holdTtl)) {
            throw ApiException.conflict("Seat " + seatId + " is already held");
        }

        // Re-check AFTER the claim: the previous hold on this seat may have been
        // confirmed (seat BOOKED, Redis key deleted post-commit) between our first
        // read and the SET NX. Without this, a stale reader could hold a sold seat.
        SeatInventory current = seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId)
                .orElse(null);
        if (current == null || current.getStatus() != SeatStatus.AVAILABLE) {
            redisHoldStore.release(eventId, seatId);
            throw ApiException.conflict("Seat " + seatId + " is already booked");
        }

        Hold hold = new Hold(holdId, eventId, seatId, user.id(), user.email(),
                HoldStatus.HELD, seat.getPrice(), expiresAt, now);
        holdRepository.save(hold);
        broadcaster.seatHeld(eventId, seatId);
        return new HoldResult(
                new HoldResponse(holdId, eventId, seatId, user.id(), expiresAt, HoldStatus.HELD.name()),
                true);
    }

    public void releaseHold(UUID holdId, JwtUser user) {
        Hold hold = holdRepository.findById(holdId)
                .orElseThrow(() -> ApiException.notFound("Hold " + holdId + " not found"));
        if (!hold.getUserId().equals(user.id())) {
            throw ApiException.forbidden("Hold " + holdId + " does not belong to you");
        }
        if (hold.getStatus() != HoldStatus.HELD) {
            throw ApiException.conflict("Hold " + holdId + " is no longer active");
        }
        hold.setStatus(HoldStatus.RELEASED);
        holdRepository.save(hold);
        redisHoldStore.release(hold.getEventId(), hold.getSeatId());
        broadcaster.seatAvailable(hold.getEventId(), hold.getSeatId());
    }

    public List<HoldSummary> myActiveHolds(JwtUser user) {
        return holdRepository.findByUserIdAndStatusAndExpiresAtAfter(user.id(), HoldStatus.HELD, Instant.now())
                .stream()
                .map(h -> new HoldSummary(h.getId(), h.getEventId(), h.getSeatId(), h.getExpiresAt()))
                .toList();
    }

    private static HoldResponse toResponse(Hold h) {
        return new HoldResponse(h.getId(), h.getEventId(), h.getSeatId(), h.getUserId(),
                h.getExpiresAt(), h.getStatus().name());
    }
}
