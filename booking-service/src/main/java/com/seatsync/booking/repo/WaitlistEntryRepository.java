package com.seatsync.booking.repo;

import com.seatsync.booking.domain.WaitlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, UUID> {

    boolean existsByEventIdAndUserId(UUID eventId, UUID userId);

    Optional<WaitlistEntry> findFirstByEventIdOrderByCreatedAtAsc(UUID eventId);

    long countByEventIdAndCreatedAtLessThanEqual(UUID eventId, Instant createdAt);

    long deleteByEventIdAndUserId(UUID eventId, UUID userId);
}
