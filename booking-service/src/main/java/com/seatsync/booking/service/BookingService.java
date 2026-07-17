package com.seatsync.booking.service;

import com.seatsync.booking.api.dto.BookingResponse;
import com.seatsync.booking.domain.Booking;
import com.seatsync.booking.domain.BookingStatus;
import com.seatsync.booking.domain.Hold;
import com.seatsync.booking.domain.HoldStatus;
import com.seatsync.booking.domain.SeatInventory;
import com.seatsync.booking.domain.SeatStatus;
import com.seatsync.booking.error.ApiException;
import com.seatsync.booking.repo.BookingRepository;
import com.seatsync.booking.repo.HoldRepository;
import com.seatsync.booking.repo.SeatInventoryRepository;
import com.seatsync.booking.security.JwtUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Booking confirm flow, per conventions section 6.3. The AVAILABLE -> BOOKED
 * transition is protected by the JPA @Version optimistic lock on seat_inventory —
 * the FINAL guard against double booking, independent of Redis hold exclusivity.
 *
 * <p>Idempotent replay (review #4): {@code bookings.hold_id} is UNIQUE, so the
 * holdId is the natural idempotency key. Re-confirming an already-CONFIRMED hold
 * returns the original booking ({@code created=false} -> HTTP 200); rivals still
 * get 403, expired/inactive holds still 409.
 *
 * <p>The BookingConfirmed event is written to the transactional outbox INSIDE
 * the confirm transaction (review #1) — the HTTP path never touches Kafka.
 */
@Service
public class BookingService {

    /** {@code created} distinguishes a fresh booking (201) from an idempotent replay (200). */
    public record ConfirmResult(BookingResponse booking, boolean created) {
    }

    private final HoldRepository holdRepository;
    private final SeatInventoryRepository seatInventoryRepository;
    private final BookingRepository bookingRepository;
    private final RedisHoldStore redisHoldStore;
    private final BookingEventPublisher eventPublisher;
    private final SeatEventBroadcaster broadcaster;
    private final EventNameResolver eventNameResolver;
    private final WaitlistService waitlistService;

    public BookingService(HoldRepository holdRepository,
                          SeatInventoryRepository seatInventoryRepository,
                          BookingRepository bookingRepository,
                          RedisHoldStore redisHoldStore,
                          BookingEventPublisher eventPublisher,
                          SeatEventBroadcaster broadcaster,
                          EventNameResolver eventNameResolver,
                          WaitlistService waitlistService) {
        this.holdRepository = holdRepository;
        this.seatInventoryRepository = seatInventoryRepository;
        this.bookingRepository = bookingRepository;
        this.redisHoldStore = redisHoldStore;
        this.eventPublisher = eventPublisher;
        this.broadcaster = broadcaster;
        this.eventNameResolver = eventNameResolver;
        this.waitlistService = waitlistService;
    }

    @Transactional
    public ConfirmResult confirm(UUID holdId, JwtUser user) {
        Hold hold = holdRepository.findById(holdId)
                .orElseThrow(() -> ApiException.notFound("Hold " + holdId + " not found"));
        if (!hold.getUserId().equals(user.id())) {
            // Ownership first: a rival replaying someone else's holdId gets 403,
            // whether the hold is live or already confirmed.
            throw ApiException.forbidden("Hold " + holdId + " does not belong to you");
        }
        if (hold.getStatus() == HoldStatus.CONFIRMED) {
            // Idempotent replay: the caller's earlier confirm succeeded but the
            // response may have been lost (e.g. gateway timeout). Return the
            // original booking instead of a scary 409.
            Booking existing = bookingRepository.findByHoldId(holdId).orElse(null);
            if (existing != null && existing.getUserId().equals(user.id())
                    && existing.getStatus() == BookingStatus.CONFIRMED) {
                return new ConfirmResult(toResponse(existing), false);
            }
            throw ApiException.conflict("Hold " + holdId + " is no longer active");
        }
        if (hold.getStatus() != HoldStatus.HELD) {
            throw ApiException.conflict("Hold " + holdId + " is no longer active");
        }
        Instant now = Instant.now();
        if (!hold.getExpiresAt().isAfter(now)) {
            throw ApiException.conflict("Hold " + holdId + " has expired");
        }

        SeatInventory seat = seatInventoryRepository
                .findByEventIdAndSeatId(hold.getEventId(), hold.getSeatId())
                .orElseThrow(() -> ApiException.notFound(
                        "Seat " + hold.getSeatId() + " not found for event " + hold.getEventId()));
        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw ApiException.conflict("Seat " + hold.getSeatId() + " was just taken");
        }
        seat.setStatus(SeatStatus.BOOKED);
        try {
            // FINAL guard: @Version optimistic lock — exactly one concurrent
            // transaction can move this row from AVAILABLE to BOOKED.
            seatInventoryRepository.saveAndFlush(seat);
        } catch (OptimisticLockingFailureException e) {
            throw ApiException.conflict("Seat " + hold.getSeatId() + " was just taken");
        }

        hold.setStatus(HoldStatus.CONFIRMED);
        holdRepository.save(hold);

        String eventName = eventNameResolver.resolve(hold.getEventId());
        Booking booking = new Booking(hold.getEventId(), eventName, hold.getSeatId(),
                hold.getUserId(), hold.getUserEmail(), hold.getPrice(), BookingStatus.CONFIRMED,
                now, hold.getId());
        Booking saved;
        try {
            saved = bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException e) {
            // uq_bookings_hold_id: an identical concurrent confirm won the insert.
            throw ApiException.conflict("Hold " + holdId + " was already confirmed");
        }

        // The domain event commits atomically with the booking (transactional outbox).
        eventPublisher.bookingConfirmed(saved);

        // Best-effort side effects run AFTER the commit (never aborting the booking).
        // Deleting the Redis key strictly post-commit means a successful SET NX by a
        // competing hold implies this booking is already visible to it.
        UUID eventId = hold.getEventId();
        String seatId = hold.getSeatId();
        TxSideEffects.afterCommit(() -> {
            redisHoldStore.release(eventId, seatId);
            broadcaster.seatBooked(eventId, seatId);
        });

        return new ConfirmResult(toResponse(saved), true);
    }

    @Transactional
    public void cancel(UUID bookingId, JwtUser user) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> ApiException.notFound("Booking " + bookingId + " not found"));
        if (!booking.getUserId().equals(user.id())) {
            throw ApiException.forbidden("Booking " + bookingId + " does not belong to you");
        }
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw ApiException.conflict("Booking " + bookingId + " is already cancelled");
        }

        SeatInventory seat = seatInventoryRepository
                .findByEventIdAndSeatId(booking.getEventId(), booking.getSeatId())
                .orElse(null);
        if (seat != null && seat.getStatus() == SeatStatus.BOOKED) {
            seat.setStatus(SeatStatus.AVAILABLE);
            try {
                seatInventoryRepository.saveAndFlush(seat);
            } catch (OptimisticLockingFailureException e) {
                throw ApiException.conflict("Seat " + booking.getSeatId() + " state changed, please retry");
            }
        }

        booking.setStatus(BookingStatus.CANCELLED);
        bookingRepository.save(booking);

        UUID eventId = booking.getEventId();
        String seatId = booking.getSeatId();
        // Register the AVAILABLE broadcast BEFORE the offer: if the offer creates
        // a hold, its post-commit HELD broadcast must land after this one.
        TxSideEffects.afterCommit(() -> broadcaster.seatAvailable(eventId, seatId));
        // Same offer logic as the expiry sweeper, inside this transaction: the
        // offer-hold + WaitlistOffered outbox row + entry removal commit (or roll
        // back) atomically with the cancellation.
        waitlistService.offerSeat(eventId, seatId);
    }

    public List<BookingResponse> myBookings(JwtUser user) {
        return bookingRepository.findByUserIdOrderByConfirmedAtDesc(user.id()).stream()
                .map(BookingService::toResponse)
                .toList();
    }

    private static BookingResponse toResponse(Booking b) {
        return new BookingResponse(b.getId(), b.getEventId(), b.getEventName(), b.getSeatId(),
                b.getPrice(), b.getStatus().name(), b.getConfirmedAt());
    }
}
