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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaitlistServiceTest {

    @Mock
    private WaitlistEntryRepository waitlistEntryRepository;
    @Mock
    private HoldRepository holdRepository;
    @Mock
    private SeatInventoryRepository seatInventoryRepository;
    @Mock
    private RedisHoldStore redisHoldStore;
    @Mock
    private BookingEventPublisher eventPublisher;
    @Mock
    private EventNameResolver eventNameResolver;
    @Mock
    private SeatEventBroadcaster broadcaster;

    private WaitlistService waitlistService;

    private final UUID eventId = UUID.randomUUID();
    private final String seatId = "A-1-4";
    /** Defaults: hold-ttl PT5M, offer-ttl PT10M. */
    private final BookingProperties properties = new BookingProperties(null, null);

    @BeforeEach
    void setUp() {
        waitlistService = new WaitlistService(waitlistEntryRepository, holdRepository,
                seatInventoryRepository, redisHoldStore, eventPublisher, eventNameResolver, broadcaster,
                properties);
    }

    @Test
    void offerCreatesTenMinuteHoldForOldestEntryOutboxesItAndRemovesTheEntry() {
        WaitlistEntry oldest = new WaitlistEntry(eventId, UUID.randomUUID(), "first@test.io",
                Instant.now().minusSeconds(600));
        when(waitlistEntryRepository.findFirstByEventIdOrderByCreatedAtAsc(eventId))
                .thenReturn(Optional.of(oldest));
        when(seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId))
                .thenReturn(Optional.of(availableSeat()));
        when(eventNameResolver.resolve(eventId)).thenReturn("Friday Night Jazz");

        waitlistService.offerSeat(eventId, seatId);

        // A REAL hold owned by the waitlisted user, 10-minute TTL, seat price carried over.
        ArgumentCaptor<Hold> holdCaptor = ArgumentCaptor.forClass(Hold.class);
        verify(holdRepository).save(holdCaptor.capture());
        Hold offerHold = holdCaptor.getValue();
        assertThat(offerHold.getUserId()).isEqualTo(oldest.getUserId());
        assertThat(offerHold.getUserEmail()).isEqualTo("first@test.io");
        assertThat(offerHold.getStatus()).isEqualTo(HoldStatus.HELD);
        assertThat(offerHold.getEventId()).isEqualTo(eventId);
        assertThat(offerHold.getSeatId()).isEqualTo(seatId);
        assertThat(offerHold.getPrice()).isEqualTo(new BigDecimal("49.00"));
        assertThat(offerHold.getExpiresAt())
                .isAfter(Instant.now().plusSeconds(590))
                .isBefore(Instant.now().plusSeconds(610));

        // WaitlistOffered goes to the outbox WITH the offer-hold id.
        verify(eventPublisher).waitlistOffered(eq(eventId), eq("Friday Night Jazz"), eq(seatId),
                eq(oldest.getUserId()), eq("first@test.io"), eq(offerHold.getId()),
                eq(offerHold.getExpiresAt()));
        verify(waitlistEntryRepository).delete(oldest);

        // Best-effort adjuncts: Redis claim with the 10-minute TTL and a HELD broadcast.
        verify(redisHoldStore).tryClaim(eventId, seatId, offerHold.getId(), oldest.getUserId(),
                offerHold.getExpiresAt(), Duration.ofMinutes(10));
        verify(broadcaster).seatHeld(eventId, seatId);
    }

    @Test
    void noOfferWhenWaitlistEmpty() {
        when(waitlistEntryRepository.findFirstByEventIdOrderByCreatedAtAsc(eventId))
                .thenReturn(Optional.empty());

        waitlistService.offerSeat(eventId, seatId);

        verify(holdRepository, never()).save(any(Hold.class));
        verify(eventPublisher, never()).waitlistOffered(any(), anyString(), anyString(), any(),
                anyString(), any(), any());
        verify(waitlistEntryRepository, never()).delete(any());
        verify(broadcaster, never()).seatHeld(any(), anyString());
    }

    @Test
    void noOfferAndEntryKeptWhenSeatIsNoLongerAvailable() {
        WaitlistEntry oldest = new WaitlistEntry(eventId, UUID.randomUUID(), "first@test.io",
                Instant.now().minusSeconds(600));
        when(waitlistEntryRepository.findFirstByEventIdOrderByCreatedAtAsc(eventId))
                .thenReturn(Optional.of(oldest));
        SeatInventory seat = availableSeat();
        seat.setStatus(SeatStatus.BOOKED);
        when(seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId))
                .thenReturn(Optional.of(seat));

        waitlistService.offerSeat(eventId, seatId);

        verify(holdRepository, never()).save(any(Hold.class));
        verify(waitlistEntryRepository, never()).delete(any());
        verify(eventPublisher, never()).waitlistOffered(any(), anyString(), anyString(), any(),
                anyString(), any(), any());
    }

    @Test
    void duplicateJoinRejectedWith409() {
        JwtUser user = new JwtUser(UUID.randomUUID(), "dup@test.io", "Dup", List.of("ATTENDEE"));
        when(waitlistEntryRepository.existsByEventIdAndUserId(eventId, user.id())).thenReturn(true);

        assertThatThrownBy(() -> waitlistService.join(eventId, user))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void configuredOfferTtlDrivesBothRedisPxAndDbExpiresAt() {
        Duration customTtl = Duration.ofSeconds(120);
        WaitlistService customService = new WaitlistService(waitlistEntryRepository, holdRepository,
                seatInventoryRepository, redisHoldStore, eventPublisher, eventNameResolver, broadcaster,
                new BookingProperties(null, customTtl));
        WaitlistEntry oldest = new WaitlistEntry(eventId, UUID.randomUUID(), "first@test.io",
                Instant.now().minusSeconds(600));
        when(waitlistEntryRepository.findFirstByEventIdOrderByCreatedAtAsc(eventId))
                .thenReturn(Optional.of(oldest));
        when(seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId))
                .thenReturn(Optional.of(availableSeat()));
        when(eventNameResolver.resolve(eventId)).thenReturn("Friday Night Jazz");

        Instant before = Instant.now();
        customService.offerSeat(eventId, seatId);

        ArgumentCaptor<Hold> holdCaptor = ArgumentCaptor.forClass(Hold.class);
        verify(holdRepository).save(holdCaptor.capture());
        Hold offerHold = holdCaptor.getValue();
        assertThat(offerHold.getExpiresAt())
                .as("DB expires_at must be now + booking.offer-ttl")
                .isAfterOrEqualTo(before.plus(customTtl).minusSeconds(1))
                .isBefore(before.plus(customTtl).plusSeconds(5));
        // Redis PX must use the SAME configured TTL.
        verify(redisHoldStore).tryClaim(eventId, seatId, offerHold.getId(), oldest.getUserId(),
                offerHold.getExpiresAt(), customTtl);
    }

    private SeatInventory availableSeat() {
        return new SeatInventory(eventId, seatId, "A", "1", 4, new BigDecimal("49.00"), SeatStatus.AVAILABLE);
    }
}
