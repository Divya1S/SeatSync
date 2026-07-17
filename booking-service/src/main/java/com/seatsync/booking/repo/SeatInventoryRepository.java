package com.seatsync.booking.repo;

import com.seatsync.booking.domain.SeatInventory;
import com.seatsync.booking.domain.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatInventoryRepository extends JpaRepository<SeatInventory, UUID> {

    boolean existsByEventId(UUID eventId);

    Optional<SeatInventory> findByEventIdAndSeatId(UUID eventId, String seatId);

    List<SeatInventory> findByEventIdOrderBySectionAscRowLabelAscSeatNumberAsc(UUID eventId);

    long countByEventId(UUID eventId);

    long countByEventIdAndStatus(UUID eventId, SeatStatus status);
}
