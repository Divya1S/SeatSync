package com.seatsync.booking.repo;

import com.seatsync.booking.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for client Idempotency-Key claims (conventions section 6.3).
 *
 * <p>The claim/store/delete methods run in REQUIRES_NEW transactions on purpose:
 * the claim must be committed (visible to concurrent duplicates) BEFORE the
 * domain work starts, and the stored outcome must survive even when the domain
 * transaction that produced a 4xx rolled back.
 */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    Optional<IdempotencyKey> findByUserIdAndEndpointAndIdemKey(UUID userId, String endpoint, String idemKey);

    /**
     * Atomically claims (user, endpoint, key): returns 1 when this caller won the
     * claim, 0 when a row already exists (replay or concurrent duplicate). The
     * insert commits immediately (REQUIRES_NEW), so an in-flight claim is visible
     * to rivals and survives any later domain rollback.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            INSERT INTO idempotency_keys (id, user_id, endpoint, idem_key, request_hash, created_at)
            VALUES (:id, :userId, :endpoint, :idemKey, :requestHash, :createdAt)
            ON CONFLICT (user_id, endpoint, idem_key) DO NOTHING
            """, nativeQuery = true)
    int tryClaim(@Param("id") UUID id,
                 @Param("userId") UUID userId,
                 @Param("endpoint") String endpoint,
                 @Param("idemKey") String idemKey,
                 @Param("requestHash") String requestHash,
                 @Param("createdAt") Instant createdAt);

    /** Stores the final response; independent of the (possibly rolled-back) domain transaction. */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE IdempotencyKey k SET k.responseStatus = :status, k.responseBody = :body
            WHERE k.userId = :userId AND k.endpoint = :endpoint AND k.idemKey = :idemKey
            """)
    int storeResponse(@Param("userId") UUID userId,
                      @Param("endpoint") String endpoint,
                      @Param("idemKey") String idemKey,
                      @Param("status") int status,
                      @Param("body") String body);

    /** Removes a claim whose outcome must NOT be replayed (5xx) so a retry re-executes. */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("DELETE FROM IdempotencyKey k WHERE k.userId = :userId AND k.endpoint = :endpoint AND k.idemKey = :idemKey")
    int deleteClaim(@Param("userId") UUID userId,
                    @Param("endpoint") String endpoint,
                    @Param("idemKey") String idemKey);

    /** 24h purge, invoked by the hold-expiry sweeper (joins its transaction). */
    @Modifying
    @Transactional
    @Query("DELETE FROM IdempotencyKey k WHERE k.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
