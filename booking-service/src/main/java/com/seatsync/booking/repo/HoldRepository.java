package com.seatsync.booking.repo;

import com.seatsync.booking.domain.Hold;
import com.seatsync.booking.domain.HoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {

    List<Hold> findByEventIdAndStatusAndExpiresAtAfter(UUID eventId, HoldStatus status, Instant cutoff);

    List<Hold> findByUserIdAndStatusAndExpiresAtAfter(UUID userId, HoldStatus status, Instant cutoff);

    /** The single active hold on a seat, if any (used for idempotent POST /holds replay). */
    Optional<Hold> findFirstByEventIdAndSeatIdAndStatusAndExpiresAtAfter(
            UUID eventId, String seatId, HoldStatus status, Instant cutoff);

    /** Active holds one user has on one event (per-user hold cap). */
    long countByUserIdAndEventIdAndStatusAndExpiresAtAfter(
            UUID userId, UUID eventId, HoldStatus status, Instant cutoff);

    /**
     * Atomically claims ONE expired hold for the calling sweeper (review #5): the
     * row stays locked until the surrounding transaction commits with its status
     * flipped to EXPIRED, so under N replicas each expired hold is won by exactly
     * one sweep. {@code SKIP LOCKED} makes rivals skip, not wait, and the explicit
     * exclusion list lets a sweep step past a hold whose processing just failed.
     * MUST be called inside a transaction; the caller marks the row EXPIRED.
     */
    @Query(value = """
            SELECT * FROM holds
            WHERE status = 'HELD' AND expires_at < :cutoff AND id NOT IN (:excludedIds)
            ORDER BY expires_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Hold> claimOneExpired(@Param("cutoff") Instant cutoff,
                                   @Param("excludedIds") Collection<UUID> excludedIds);
}
