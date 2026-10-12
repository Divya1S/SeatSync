package com.seatsync.booking.service;

import com.seatsync.booking.domain.Hold;
import com.seatsync.booking.domain.HoldStatus;
import com.seatsync.booking.repo.HoldRepository;
import com.seatsync.booking.repo.IdempotencyKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-hold sweep semantics: each expired hold is claimed and processed in its
 * own transaction; a failing hold is skipped for the rest of the run (so it
 * cannot stall the backlog) and retried next sweep; side effects run after
 * commit (immediately here, since a unit test has no live transaction).
 */
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
    @Mock
    private PlatformTransactionManager transactionManager;

    private HoldExpiryService holdExpiryService;

    private final UUID eventId = UUID.randomUUID();
    private final String seatId = "A-1-4";

    @BeforeEach
    void setUp() {
        holdExpiryService = new HoldExpiryService(holdRepository, redisHoldStore, eventPublisher,
                broadcaster, eventNameResolver, waitlistService, idempotencyKeyRepository, transactionManager);
    }

    @Test
    void claimedHoldIsMarkedExpiredOutboxedThenReleasedBroadcastAndOffered() {
        Hold hold = expiredHold();
        when(holdRepository.claimOneExpired(any(Instant.class), anyCollection()))
                .thenReturn(Optional.of(hold), Optional.empty());
        when(eventNameResolver.resolve(eventId)).thenReturn("Friday Night Jazz");

        holdExpiryService.expireHolds();

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        InOrder order = inOrder(holdRepository, eventPublisher, broadcaster, waitlistService);
        order.verify(holdRepository).save(hold);
        order.verify(eventPublisher).holdExpired(hold, "Friday Night Jazz");
        order.verify(broadcaster).seatAvailable(eventId, seatId);
        order.verify(waitlistService).offerSeat(eventId, seatId);
        verify(redisHoldStore).release(eventId, seatId);
        // One transaction per hold, committed.
        verify(transactionManager, times(2)).getTransaction(any());
        verify(transactionManager, times(2)).commit(any());
    }

    @Test
    void aFailingHoldRollsBackAloneAndTheSweepContinuesWithTheNextOne() {
        Hold failing = expiredHold();
        Hold ok = expiredHold();
        when(holdRepository.claimOneExpired(any(Instant.class), anyCollection()))
                .thenReturn(Optional.of(failing), Optional.of(ok), Optional.empty());
        when(eventNameResolver.resolve(eventId)).thenReturn("Friday Night Jazz");
        doThrow(new RuntimeException("outbox write failed"))
                .when(eventPublisher).holdExpired(failing, "Friday Night Jazz");

        assertThatCode(() -> holdExpiryService.expireHolds()).doesNotThrowAnyException();

        assertThat(ok.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        verify(eventPublisher).holdExpired(ok, "Friday Night Jazz");
        verify(waitlistService, times(1)).offerSeat(eventId, seatId);
        verify(broadcaster, times(1)).seatAvailable(eventId, seatId);
        verify(transactionManager).rollback(any());
        verify(transactionManager, times(2)).commit(any());

        // The failed hold is excluded from the later claims of this run.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> excluded = ArgumentCaptor.forClass(Collection.class);
        verify(holdRepository, times(3)).claimOneExpired(any(Instant.class), excluded.capture());
        assertThat(excluded.getAllValues().get(2)).contains(failing.getId());
    }

    @Test
    void sweepPurgesIdempotencyKeysOlderThan24Hours() {
        when(holdRepository.claimOneExpired(any(Instant.class), anyCollection())).thenReturn(Optional.empty());

        holdExpiryService.expireHolds();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(idempotencyKeyRepository).deleteByCreatedAtBefore(cutoff.capture());
        assertThat(cutoff.getValue())
                .isCloseTo(Instant.now().minus(Duration.ofHours(24)), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void idempotencyPurgeFailureDoesNotAbortTheSweep() {
        Hold hold = expiredHold();
        when(holdRepository.claimOneExpired(any(Instant.class), anyCollection()))
                .thenReturn(Optional.of(hold), Optional.empty());
        when(eventNameResolver.resolve(eventId)).thenReturn("Friday Night Jazz");
        doThrow(new RuntimeException("db hiccup"))
                .when(idempotencyKeyRepository).deleteByCreatedAtBefore(any(Instant.class));

        assertThatCode(() -> holdExpiryService.expireHolds()).doesNotThrowAnyException();

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        verify(holdRepository).save(hold);
    }

    @Test
    void aClaimFailureEndsTheRunQuietlyAndStillPurges() {
        when(holdRepository.claimOneExpired(any(Instant.class), anyCollection()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> holdExpiryService.expireHolds()).doesNotThrowAnyException();

        verify(eventPublisher, never()).holdExpired(any(), any());
        verify(idempotencyKeyRepository).deleteByCreatedAtBefore(any(Instant.class));
    }

    private Hold expiredHold() {
        return new Hold(UUID.randomUUID(), eventId, seatId, UUID.randomUUID(), "user@test.io",
                HoldStatus.HELD, new BigDecimal("49.00"),
                Instant.now().minusSeconds(30), Instant.now().minusSeconds(330));
    }
}
