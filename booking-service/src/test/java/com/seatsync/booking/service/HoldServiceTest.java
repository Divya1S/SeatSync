package com.seatsync.booking.service;

import com.seatsync.booking.config.BookingProperties;
import com.seatsync.booking.domain.Hold;
import com.seatsync.booking.domain.HoldStatus;
import com.seatsync.booking.domain.SeatInventory;
import com.seatsync.booking.domain.SeatStatus;
import com.seatsync.booking.error.ApiException;
import com.seatsync.booking.repo.HoldRepository;
import com.seatsync.booking.repo.SeatInventoryRepository;
import com.seatsync.booking.security.JwtUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class HoldServiceTest {

    @Mock
    private InventoryService inventoryService;
    @Mock
    private SeatInventoryRepository seatInventoryRepository;
    @Mock
    private HoldRepository holdRepository;
    @Mock
    private RedisHoldStore redisHoldStore;
    @Mock
    private SeatEventBroadcaster broadcaster;

    private HoldService holdService;

    private final UUID eventId = UUID.randomUUID();
    private final String seatId = "A-1-4";
    private final JwtUser user = new JwtUser(UUID.randomUUID(), "alice@test.io", "Alice", List.of("ATTENDEE"));
    /** Defaults: hold-ttl PT5M, offer-ttl PT10M. */
    private final BookingProperties properties = new BookingProperties(null, null);

    @BeforeEach
    void setUp() {
        holdService = new HoldService(inventoryService, seatInventoryRepository, holdRepository,
                redisHoldStore, broadcaster, properties);
    }

    @Test
    void conflictWhenRedisClaimAlreadyExists() {
        when(seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId))
                .thenReturn(Optional.of(availableSeat()));
        when(redisHoldStore.tryClaim(eq(eventId), eq(seatId), any(), eq(user.id()), any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> holdService.createHold(eventId, seatId, user))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("already held");

        verify(holdRepository, never()).save(any(Hold.class));
        verify(broadcaster, never()).seatHeld(any(), anyString());
    }

    @Test
    void conflictWhenSeatAlreadyBooked() {
        SeatInventory seat = availableSeat();
        seat.setStatus(SeatStatus.BOOKED);
        when(seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId))
                .thenReturn(Optional.of(seat));

        assertThatThrownBy(() -> holdService.createHold(eventId, seatId, user))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("already booked");

        verify(redisHoldStore, never()).tryClaim(any(), anyString(), any(), any(), any(), any());
        verify(holdRepository, never()).save(any(Hold.class));
    }

    @Test
    void conflictWhenSeatSoldBetweenReadAndClaim() {
        SeatInventory bookedNow = availableSeat();
        bookedNow.setStatus(SeatStatus.BOOKED);
        // First read sees AVAILABLE; the post-claim re-check sees BOOKED
        // (previous hold was confirmed and its Redis key deleted post-commit).
        when(seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId))
                .thenReturn(Optional.of(availableSeat()), Optional.of(bookedNow));
        when(redisHoldStore.tryClaim(eq(eventId), eq(seatId), any(), eq(user.id()), any(), eq(properties.holdTtl())))
                .thenReturn(true);

        assertThatThrownBy(() -> holdService.createHold(eventId, seatId, user))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("already booked");

        verify(redisHoldStore).release(eventId, seatId);
        verify(holdRepository, never()).save(any(Hold.class));
    }

    @Test
    void notFoundWhenSeatUnknown() {
        when(seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> holdService.createHold(eventId, seatId, user))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void repeatRequestBySameUserReplaysExistingHoldWithoutNewClaim() {
        when(seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId))
                .thenReturn(Optional.of(availableSeat()));
        Hold existing = new Hold(UUID.randomUUID(), eventId, seatId, user.id(), user.email(),
                HoldStatus.HELD, new BigDecimal("49.00"),
                Instant.now().plusSeconds(200), Instant.now().minusSeconds(100));
        when(holdRepository.findFirstByEventIdAndSeatIdAndStatusAndExpiresAtAfter(
                eq(eventId), eq(seatId), eq(HoldStatus.HELD), any()))
                .thenReturn(Optional.of(existing));

        HoldService.HoldResult result = holdService.createHold(eventId, seatId, user);

        assertThat(result.created()).as("a replay must not report a fresh creation").isFalse();
        assertThat(result.hold().holdId()).isEqualTo(existing.getId());
        assertThat(result.hold().expiresAt()).isEqualTo(existing.getExpiresAt());
        verify(redisHoldStore, never()).tryClaim(any(), anyString(), any(), any(), any(), any());
        verify(holdRepository, never()).save(any(Hold.class));
        verify(broadcaster, never()).seatHeld(any(), anyString());
    }

    @Test
    void activeHoldOwnedByAnotherUserStillYields409() {
        when(seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId))
                .thenReturn(Optional.of(availableSeat()));
        Hold rivals = new Hold(UUID.randomUUID(), eventId, seatId, UUID.randomUUID(), "rival@test.io",
                HoldStatus.HELD, new BigDecimal("49.00"),
                Instant.now().plusSeconds(200), Instant.now().minusSeconds(100));
        when(holdRepository.findFirstByEventIdAndSeatIdAndStatusAndExpiresAtAfter(
                eq(eventId), eq(seatId), eq(HoldStatus.HELD), any()))
                .thenReturn(Optional.of(rivals));

        assertThatThrownBy(() -> holdService.createHold(eventId, seatId, user))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("already held");

        verify(redisHoldStore, never()).tryClaim(any(), anyString(), any(), any(), any(), any());
        verify(holdRepository, never()).save(any(Hold.class));
    }

    @Test
    void configuredHoldTtlDrivesBothRedisPxAndDbExpiresAt() {
        // A non-default booking.hold-ttl must flow into the Redis claim TTL (PX)
        // AND the persisted expires_at — the two must never diverge.
        Duration customTtl = Duration.ofSeconds(90);
        HoldService customService = new HoldService(inventoryService, seatInventoryRepository,
                holdRepository, redisHoldStore, broadcaster,
                new BookingProperties(customTtl, null));
        when(seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId))
                .thenReturn(Optional.of(availableSeat()));
        when(redisHoldStore.tryClaim(eq(eventId), eq(seatId), any(), eq(user.id()), any(), eq(customTtl)))
                .thenReturn(true);
        when(holdRepository.save(any(Hold.class))).thenAnswer(inv -> inv.getArgument(0));

        Instant before = Instant.now();
        HoldService.HoldResult result = customService.createHold(eventId, seatId, user);

        assertThat(result.created()).isTrue();
        assertThat(result.hold().expiresAt())
                .as("DB expires_at must be now + booking.hold-ttl")
                .isAfterOrEqualTo(before.plus(customTtl).minusSeconds(1))
                .isBefore(before.plus(customTtl).plusSeconds(5));
        // The eq(customTtl) stub above is the Redis PX assertion: a different TTL
        // would leave the stub unmatched and the claim would return false.
        verify(redisHoldStore).tryClaim(eq(eventId), eq(seatId), any(), eq(user.id()), any(), eq(customTtl));
    }

    @Test
    void happyPathClaimsRedisPersistsHoldAndBroadcasts() {
        when(seatInventoryRepository.findByEventIdAndSeatId(eventId, seatId))
                .thenReturn(Optional.of(availableSeat()));
        when(redisHoldStore.tryClaim(eq(eventId), eq(seatId), any(), eq(user.id()), any(), eq(properties.holdTtl())))
                .thenReturn(true);
        when(holdRepository.save(any(Hold.class))).thenAnswer(inv -> inv.getArgument(0));

        HoldService.HoldResult result = holdService.createHold(eventId, seatId, user);

        assertThat(result.created()).isTrue();
        assertThat(result.hold().status()).isEqualTo("HELD");
        assertThat(result.hold().eventId()).isEqualTo(eventId);
        assertThat(result.hold().seatId()).isEqualTo(seatId);
        assertThat(result.hold().userId()).isEqualTo(user.id());
        assertThat(result.hold().expiresAt()).isAfter(Instant.now());
        verify(inventoryService).ensureSeeded(eventId);
        verify(holdRepository).save(any(Hold.class));
        verify(broadcaster).seatHeld(eventId, seatId);
    }

    // --- releaseHold: the hold-lifecycle invariant path (PIT-targeted; §6.3) ---

    @Test
    void releaseByOwnerMarksReleasedFreesRedisKeyAndBroadcastsAvailable() {
        UUID holdId = UUID.randomUUID();
        Hold hold = heldBy(holdId, user.id());
        when(holdRepository.findById(holdId)).thenReturn(Optional.of(hold));

        holdService.releaseHold(holdId, user);

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
        verify(holdRepository).save(hold);
        verify(redisHoldStore).release(eventId, seatId);
        verify(broadcaster).seatAvailable(eventId, seatId);
    }

    @Test
    void releaseOfSomeoneElsesHoldIsRejectedWith403() {
        UUID holdId = UUID.randomUUID();
        Hold rivals = heldBy(holdId, UUID.randomUUID());
        when(holdRepository.findById(holdId)).thenReturn(Optional.of(rivals));

        assertThatThrownBy(() -> holdService.releaseHold(holdId, user))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        assertThat(rivals.getStatus()).isEqualTo(HoldStatus.HELD);
        verify(holdRepository, never()).save(any(Hold.class));
        verify(redisHoldStore, never()).release(any(), anyString());
        verify(broadcaster, never()).seatAvailable(any(), anyString());
    }

    @Test
    void releaseOfInactiveHoldIsRejectedWith409() {
        UUID holdId = UUID.randomUUID();
        Hold confirmed = heldBy(holdId, user.id());
        confirmed.setStatus(HoldStatus.CONFIRMED);
        when(holdRepository.findById(holdId)).thenReturn(Optional.of(confirmed));

        assertThatThrownBy(() -> holdService.releaseHold(holdId, user))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(confirmed.getStatus()).isEqualTo(HoldStatus.CONFIRMED);
        verify(holdRepository, never()).save(any(Hold.class));
        verify(redisHoldStore, never()).release(any(), anyString());
    }

    private Hold heldBy(UUID holdId, UUID userId) {
        return new Hold(holdId, eventId, seatId, userId, "holder@test.io",
                HoldStatus.HELD, new BigDecimal("49.00"),
                Instant.now().plusSeconds(200), Instant.now().minusSeconds(100));
    }

    private SeatInventory availableSeat() {
        return new SeatInventory(eventId, seatId, "A", "1", 4, new BigDecimal("49.00"), SeatStatus.AVAILABLE);
    }
}
