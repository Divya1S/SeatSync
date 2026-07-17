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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private HoldRepository holdRepository;
    @Mock
    private SeatInventoryRepository seatInventoryRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private RedisHoldStore redisHoldStore;
    @Mock
    private BookingEventPublisher eventPublisher;
    @Mock
    private SeatEventBroadcaster broadcaster;
    @Mock
    private EventNameResolver eventNameResolver;
    @Mock
    private WaitlistService waitlistService;

    private BookingService bookingService;

    private final UUID eventId = UUID.randomUUID();
    private final String seatId = "A-1-4";
    private final UUID holdId = UUID.randomUUID();
    private final JwtUser owner = new JwtUser(UUID.randomUUID(), "owner@test.io", "Owner", List.of("ATTENDEE"));

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(holdRepository, seatInventoryRepository, bookingRepository,
                redisHoldStore, eventPublisher, broadcaster, eventNameResolver, waitlistService);
    }

    @Test
    void expiredHoldIsRejectedWith409() {
        Hold hold = hold(owner.id(), Instant.now().minusSeconds(1));
        when(holdRepository.findById(holdId)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> bookingService.confirm(holdId, owner))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("expired");

        verify(bookingRepository, never()).saveAndFlush(any(Booking.class));
        verify(seatInventoryRepository, never()).saveAndFlush(any(SeatInventory.class));
    }

    @Test
    void holdOwnedBySomeoneElseIsRejectedWith403() {
        Hold hold = hold(UUID.randomUUID(), Instant.now().plusSeconds(120));
        when(holdRepository.findById(holdId)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> bookingService.confirm(holdId, owner))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(bookingRepository, never()).saveAndFlush(any(Booking.class));
    }

    @Test
    void confirmedHoldWithoutOwnBookingIsRejectedWith409() {
        Hold hold = hold(owner.id(), Instant.now().plusSeconds(120));
        hold.setStatus(HoldStatus.CONFIRMED);
        when(holdRepository.findById(holdId)).thenReturn(Optional.of(hold));
        // Mockito's default Optional.empty() for findByHoldId: no booking to replay.

        assertThatThrownBy(() -> bookingService.confirm(holdId, owner))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void releasedHoldIsRejectedWith409() {
        Hold hold = hold(owner.id(), Instant.now().plusSeconds(120));
        hold.setStatus(HoldStatus.RELEASED);
        when(holdRepository.findById(holdId)).thenReturn(Optional.of(hold));

        assertThatThrownBy(() -> bookingService.confirm(holdId, owner))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void replayOfConfirmedHoldBySameCallerReturnsOriginalBookingNotCreated() {
        Hold hold = hold(owner.id(), Instant.now().plusSeconds(120));
        hold.setStatus(HoldStatus.CONFIRMED);
        when(holdRepository.findById(holdId)).thenReturn(Optional.of(hold));
        Booking existing = new Booking(eventId, "Friday Night Jazz", seatId, owner.id(),
                owner.email(), new BigDecimal("49.00"), BookingStatus.CONFIRMED,
                Instant.now().minusSeconds(30), holdId);
        when(bookingRepository.findByHoldId(holdId)).thenReturn(Optional.of(existing));

        BookingService.ConfirmResult result = bookingService.confirm(holdId, owner);

        assertThat(result.created()).as("replay must not report a fresh creation").isFalse();
        assertThat(result.booking().seatId()).isEqualTo(seatId);
        assertThat(result.booking().status()).isEqualTo("CONFIRMED");
        verify(seatInventoryRepository, never()).saveAndFlush(any(SeatInventory.class));
        verify(bookingRepository, never()).saveAndFlush(any(Booking.class));
        verify(eventPublisher, never()).bookingConfirmed(any());
    }

    @Test
    void optimisticLockLossMapsTo409() {
        Hold hold = hold(owner.id(), Instant.now().plusSeconds(120));
        when(holdRepository.findById(holdId)).thenReturn(Optional.of(hold));
        when(seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId))
                .thenReturn(Optional.of(availableSeat()));
        when(seatInventoryRepository.saveAndFlush(any(SeatInventory.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(SeatInventory.class, seatId));

        assertThatThrownBy(() -> bookingService.confirm(holdId, owner))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("just taken");

        verify(bookingRepository, never()).saveAndFlush(any(Booking.class));
        verify(eventPublisher, never()).bookingConfirmed(any());
    }

    @Test
    void seatAlreadyBookedMapsTo409() {
        Hold hold = hold(owner.id(), Instant.now().plusSeconds(120));
        SeatInventory seat = availableSeat();
        seat.setStatus(SeatStatus.BOOKED);
        when(holdRepository.findById(holdId)).thenReturn(Optional.of(hold));
        when(seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId)).thenReturn(Optional.of(seat));

        assertThatThrownBy(() -> bookingService.confirm(holdId, owner))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(seatInventoryRepository, never()).saveAndFlush(any(SeatInventory.class));
    }

    @Test
    void happyPathBooksSeatMarksHoldWritesOutboxAndBroadcasts() {
        Hold hold = hold(owner.id(), Instant.now().plusSeconds(120));
        SeatInventory seat = availableSeat();
        when(holdRepository.findById(holdId)).thenReturn(Optional.of(hold));
        when(seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId)).thenReturn(Optional.of(seat));
        when(seatInventoryRepository.saveAndFlush(any(SeatInventory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(eventNameResolver.resolve(eventId)).thenReturn("Friday Night Jazz");
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingService.ConfirmResult result = bookingService.confirm(holdId, owner);
        BookingResponse response = result.booking();

        assertThat(result.created()).isTrue();
        assertThat(response.status()).isEqualTo("CONFIRMED");
        assertThat(response.eventName()).isEqualTo("Friday Night Jazz");
        assertThat(response.seatId()).isEqualTo(seatId);
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.BOOKED);
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CONFIRMED);
        verify(redisHoldStore).release(eventId, seatId);
        verify(eventPublisher).bookingConfirmed(any(Booking.class));
        verify(broadcaster).seatBooked(eventId, seatId);
    }

    // --- cancel: the seat-release invariant path (PIT-targeted; §6.3) ---

    @Test
    void cancelByOwnerFreesSeatCancelsBookingBroadcastsAndOffersSeatToWaitlist() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = confirmedBooking();
        SeatInventory seat = availableSeat();
        seat.setStatus(SeatStatus.BOOKED);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId)).thenReturn(Optional.of(seat));
        when(seatInventoryRepository.saveAndFlush(any(SeatInventory.class))).thenAnswer(inv -> inv.getArgument(0));

        bookingService.cancel(bookingId, owner);

        assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        verify(bookingRepository).save(booking);
        verify(broadcaster).seatAvailable(eventId, seatId);
        verify(waitlistService).offerSeat(eventId, seatId);
    }

    @Test
    void cancelBySomeoneElseIsRejectedWith403() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = confirmedBooking();
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        JwtUser rival = new JwtUser(UUID.randomUUID(), "rival@test.io", "Rival", List.of("ATTENDEE"));

        assertThatThrownBy(() -> bookingService.cancel(bookingId, rival))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(seatInventoryRepository, never()).saveAndFlush(any(SeatInventory.class));
        verify(waitlistService, never()).offerSeat(any(), any());
    }

    @Test
    void cancelOfAlreadyCancelledBookingIsRejectedWith409() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = confirmedBooking();
        booking.setStatus(BookingStatus.CANCELLED);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancel(bookingId, owner))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(seatInventoryRepository, never()).saveAndFlush(any(SeatInventory.class));
        verify(waitlistService, never()).offerSeat(any(), any());
    }

    @Test
    void cancelSeatOptimisticLockLossMapsTo409AndLeavesBookingConfirmed() {
        UUID bookingId = UUID.randomUUID();
        Booking booking = confirmedBooking();
        SeatInventory seat = availableSeat();
        seat.setStatus(SeatStatus.BOOKED);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId)).thenReturn(Optional.of(seat));
        when(seatInventoryRepository.saveAndFlush(any(SeatInventory.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(SeatInventory.class, seatId));

        assertThatThrownBy(() -> bookingService.cancel(bookingId, owner))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository, never()).save(any(Booking.class));
        verify(waitlistService, never()).offerSeat(any(), any());
    }

    private Booking confirmedBooking() {
        return new Booking(eventId, "Friday Night Jazz", seatId, owner.id(), owner.email(),
                new BigDecimal("49.00"), BookingStatus.CONFIRMED, Instant.now().minusSeconds(60), holdId);
    }

    private Hold hold(UUID userId, Instant expiresAt) {
        return new Hold(holdId, eventId, seatId, userId, "owner@test.io",
                HoldStatus.HELD, new BigDecimal("49.00"), expiresAt, Instant.now().minusSeconds(10));
    }

    private SeatInventory availableSeat() {
        return new SeatInventory(eventId, seatId, "A", "1", 4, new BigDecimal("49.00"), SeatStatus.AVAILABLE);
    }
}
