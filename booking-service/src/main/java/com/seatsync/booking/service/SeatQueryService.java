package com.seatsync.booking.service;

import com.seatsync.booking.api.dto.SeatView;
import com.seatsync.booking.api.dto.SeatsResponse;
import com.seatsync.booking.api.dto.StatsResponse;
import com.seatsync.booking.domain.BookingStatus;
import com.seatsync.booking.domain.Hold;
import com.seatsync.booking.domain.HoldStatus;
import com.seatsync.booking.domain.SeatInventory;
import com.seatsync.booking.domain.SeatStatus;
import com.seatsync.booking.repo.BookingRepository;
import com.seatsync.booking.repo.HoldRepository;
import com.seatsync.booking.repo.SeatInventoryRepository;
import com.seatsync.booking.security.JwtUser;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read model: live seat view (HELD computed by overlaying active holds on
 * inventory) and organizer stats.
 */
@Service
public class SeatQueryService {

    private final SeatInventoryRepository seatInventoryRepository;
    private final HoldRepository holdRepository;
    private final BookingRepository bookingRepository;

    public SeatQueryService(SeatInventoryRepository seatInventoryRepository,
                            HoldRepository holdRepository,
                            BookingRepository bookingRepository) {
        this.seatInventoryRepository = seatInventoryRepository;
        this.holdRepository = holdRepository;
        this.bookingRepository = bookingRepository;
    }

    public SeatsResponse liveSeats(UUID eventId, JwtUser caller) {
        List<SeatInventory> inventory =
                seatInventoryRepository.findByEventIdOrderBySectionAscRowLabelAscSeatNumberAsc(eventId);
        Map<String, Hold> activeHolds = holdRepository
                .findByEventIdAndStatusAndExpiresAtAfter(eventId, HoldStatus.HELD, Instant.now())
                .stream()
                .collect(Collectors.toMap(Hold::getSeatId, Function.identity(), (a, b) -> a));

        List<SeatView> seats = new ArrayList<>(inventory.size());
        for (SeatInventory seat : inventory) {
            Hold hold = activeHolds.get(seat.getSeatId());
            if (seat.getStatus() == SeatStatus.BOOKED) {
                seats.add(new SeatView(seat.getSeatId(), SeatStatus.BOOKED.name(), false, null));
            } else if (hold != null) {
                boolean mine = caller != null && hold.getUserId().equals(caller.id());
                seats.add(new SeatView(seat.getSeatId(), "HELD", mine, mine ? hold.getExpiresAt() : null));
            } else {
                seats.add(new SeatView(seat.getSeatId(), SeatStatus.AVAILABLE.name(), false, null));
            }
        }
        return new SeatsResponse(eventId, seats);
    }

    public StatsResponse stats(UUID eventId) {
        long total = seatInventoryRepository.countByEventId(eventId);
        long booked = seatInventoryRepository.countByEventIdAndStatus(eventId, SeatStatus.BOOKED);
        long held = holdRepository
                .findByEventIdAndStatusAndExpiresAtAfter(eventId, HoldStatus.HELD, Instant.now())
                .size();
        long available = Math.max(0, total - booked - held);
        BigDecimal revenue = bookingRepository.sumPriceByEventIdAndStatus(eventId, BookingStatus.CONFIRMED);
        if (revenue == null) {
            revenue = BigDecimal.ZERO;
        }
        return new StatsResponse(eventId, total, available, held, booked, revenue);
    }
}
