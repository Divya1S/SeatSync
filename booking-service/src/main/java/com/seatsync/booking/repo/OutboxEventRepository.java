package com.seatsync.booking.repo;

import com.seatsync.booking.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims a batch of unpublished rows for this relay instance. {@code FOR UPDATE
     * SKIP LOCKED} makes the claim atomic under N replicas: rows locked by another
     * relay's in-flight transaction are skipped, never processed twice concurrently.
     * Must run inside a transaction that spans the publish attempt.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimUnpublishedBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Query(value = "DELETE FROM outbox_events WHERE published_at IS NOT NULL AND published_at < :cutoff",
            nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
