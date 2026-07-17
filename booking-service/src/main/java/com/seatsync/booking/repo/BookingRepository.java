package com.seatsync.booking.repo;

import com.seatsync.booking.domain.Booking;
import com.seatsync.booking.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByUserIdOrderByConfirmedAtDesc(UUID userId);

    /** hold_id is UNIQUE — at most one booking per hold (idempotent replay, section 6.3). */
    Optional<Booking> findByHoldId(UUID holdId);

    @Query("select coalesce(sum(b.price), 0) from Booking b where b.eventId = :eventId and b.status = :status")
    BigDecimal sumPriceByEventIdAndStatus(@Param("eventId") UUID eventId, @Param("status") BookingStatus status);
}
