package com.seatsync.booking.service;

import com.seatsync.booking.domain.Hold;
import com.seatsync.booking.domain.HoldStatus;
import com.seatsync.booking.repo.HoldRepository;
import com.seatsync.booking.repo.IdempotencyKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HoldExpiryServiceTest {

    @Mock
    private HoldRepository holdRepository;
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
    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private HoldExpiryService holdExpiryService;

    private final UUID eventId = UUID.randomUUID();
    private final String seatId = "A-1-4";

    @BeforeEach
    void setUp() {
        holdExpiryService = new HoldExpiryService(holdRepository, redisHoldStore, eventPublisher,
                broadcaster, eventNameResolver, waitlistService, idempotencyKeyRepository);
    }

    @Test
    void claimedHoldIsMarkedExpiredOutboxedBroadcastAndSeatOfferedToWaitlist() {
        Hold hold = expiredHold();
        when(holdRepository.claimExpired(any(Instant.class))).thenReturn(List.of(hold));
        when(eventNameResolver.resolve(eventId)).thenReturn("Friday Night Jazz");

        holdExpiryService.expireHolds();

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        verify(holdRepository).save(hold);
        verify(redisHoldStore).release(eventId, seatId);
        verify(eventPublisher).holdExpired(hold, "Friday Night Jazz");
        verify(broadcaster).seatAvailable(eventId, seatId);
        verify(waitlistService).offerSeat(eventId, seatId);
    }

    @Test
    void failureOnOneHoldDoesNotAbortTheSweep() {
        Hold failing = expiredHold();
        Hold ok = expiredHold();
        when(holdRepository.claimExpired(any(Instant.class))).thenReturn(List.of(failing, ok));
        doThrow(new RuntimeException("redis hiccup")).doNothing()
                .when(redisHoldStore).release(eventId, seatId);
        when(eventNameResolver.resolve(eventId)).thenReturn("Friday Night Jazz");

        holdExpiryService.expireHolds();

        assertThat(ok.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        verify(waitlistService).offerSeat(eventId, seatId);
        verify(eventPublisher).holdExpired(ok, "Friday Night Jazz");
    }

    @Test
    void sweepPurgesIdempotencyKeysOlderThan24Hours() {
        when(holdRepository.claimExpired(any(Instant.class))).thenReturn(List.of());

        holdExpiryService.expireHolds();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(idempotencyKeyRepository).deleteByCreatedAtBefore(cutoff.capture());
        assertThat(cutoff.getValue())
                .isCloseTo(Instant.now().minus(Duration.ofHours(24)), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void idempotencyPurgeFailureDoesNotAbortTheSweep() {
        Hold hold = expiredHold();
        when(holdRepository.claimExpired(any(Instant.class))).thenReturn(List.of(hold));
        when(eventNameResolver.resolve(eventId)).thenReturn("Friday Night Jazz");
        doThrow(new RuntimeException("db hiccup"))
                .when(idempotencyKeyRepository).deleteByCreatedAtBefore(any(Instant.class));

        holdExpiryService.expireHolds();

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        verify(holdRepository).save(hold);
    }

    private Hold expiredHold() {
        return new Hold(UUID.randomUUID(), eventId, seatId, UUID.randomUUID(), "user@test.io",
                HoldStatus.HELD, new BigDecimal("49.00"),
                Instant.now().minusSeconds(30), Instant.now().minusSeconds(330));
    }
}
