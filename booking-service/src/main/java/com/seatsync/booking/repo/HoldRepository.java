package com.seatsync.booking.repo;

import com.seatsync.booking.domain.Hold;
import com.seatsync.booking.domain.HoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {

    List<Hold> findByEventIdAndStatusAndExpiresAtAfter(UUID eventId, HoldStatus status, Instant cutoff);

    List<Hold> findByUserIdAndStatusAndExpiresAtAfter(UUID userId, HoldStatus status, Instant cutoff);

    /** The single active hold on a seat, if any (used for idempotent POST /holds replay). */
    Optional<Hold> findFirstByEventIdAndSeatIdAndStatusAndExpiresAtAfter(
            UUID eventId, String seatId, HoldStatus status, Instant cutoff);

    /**
     * Atomically claims expired holds for the calling sweeper (review #5): rows
     * locked here stay locked until the surrounding transaction commits (with the
     * status flipped to EXPIRED), so under N replicas each expired hold is won by
     * exactly one sweep — {@code SKIP LOCKED} makes rivals skip, not wait. MUST be
     * called inside a transaction; the caller marks the returned rows EXPIRED.
     */
    @Query(value = """
            SELECT * FROM holds
            WHERE status = 'HELD' AND expires_at < :cutoff
            ORDER BY expires_at
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Hold> claimExpired(@Param("cutoff") Instant cutoff);
}
