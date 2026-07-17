package com.seatsync.booking.service;

import com.seatsync.booking.catalog.CatalogGateway;
import com.seatsync.booking.catalog.EventDto;
import com.seatsync.booking.catalog.SeatMapDto;
import com.seatsync.booking.domain.SeatStatus;
import com.seatsync.booking.error.ApiException;
import com.seatsync.booking.repo.SeatInventoryRepository;
import feign.FeignException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Lazily seeds seat inventory from the catalog service the first time an event is
 * touched. Seeding is IDEMPOTENT (review #7): every row is inserted with
 * {@code ON CONFLICT (event_id, seat_id) DO NOTHING}, so concurrent seeders
 * converge on the same inventory instead of failing. The Redisson lock
 * {@code lock:inventory:{eventId}} is a stampede-reduction optimization ONLY —
 * correctness comes from the unique constraint; a lost or expired lock may cost
 * latency (duplicate catalog fetches), never correctness.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);
    private static final String CATALOG_UNAVAILABLE = "Event catalog temporarily unavailable";

    private static final String INSERT_SEAT_SQL = """
            INSERT INTO seat_inventory
                (id, event_id, seat_id, section, row_label, seat_number, price, status, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
            ON CONFLICT (event_id, seat_id) DO NOTHING
            """;

    private record SeatRow(String seatId, String section, String rowLabel, int seatNumber, BigDecimal price) {
    }

    private final SeatInventoryRepository seatInventoryRepository;
    private final CatalogGateway catalogGateway;
    private final EventNameResolver eventNameResolver;
    private final RedissonClient redissonClient;
    private final JdbcTemplate jdbcTemplate;

    public InventoryService(SeatInventoryRepository seatInventoryRepository,
                            CatalogGateway catalogGateway,
                            EventNameResolver eventNameResolver,
                            RedissonClient redissonClient,
                            JdbcTemplate jdbcTemplate) {
        this.seatInventoryRepository = seatInventoryRepository;
        this.catalogGateway = catalogGateway;
        this.eventNameResolver = eventNameResolver;
        this.redissonClient = redissonClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ensureSeeded(UUID eventId) {
        if (seatInventoryRepository.existsByEventId(eventId)) {
            return;
        }
        RLock lock = redissonClient.getLock("lock:inventory:" + eventId);
        boolean locked = false;
        try {
            try {
                locked = lock.tryLock(10, 30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // With or without the lock, proceed: seeding is idempotent, so a
            // concurrent seeder converges instead of erroring. Not holding the
            // lock only means we might fetch the catalog redundantly.
            if (seatInventoryRepository.existsByEventId(eventId)) {
                return;
            }
            seedFromCatalog(eventId);
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    private void seedFromCatalog(UUID eventId) {
        EventDto event;
        SeatMapDto seatMap;
        try {
            event = catalogGateway.getEvent(eventId);
            seatMap = catalogGateway.getSeatMap(eventId);
        } catch (FeignException.NotFound e) {
            throw ApiException.notFound("Event " + eventId + " not found in catalog");
        } catch (Exception e) {
            log.warn("Catalog unreachable while seeding inventory for event {}: {}", eventId, e.toString());
            throw ApiException.serviceUnavailable(CATALOG_UNAVAILABLE);
        }
        if (event == null || seatMap == null || seatMap.sections() == null) {
            throw ApiException.serviceUnavailable(CATALOG_UNAVAILABLE);
        }
        if (!"PUBLISHED".equalsIgnoreCase(event.status())) {
            throw ApiException.conflict("Event " + eventId + " is not open for booking");
        }
        List<SeatRow> rows = new ArrayList<>();
        for (SeatMapDto.Section section : seatMap.sections()) {
            if (section.rows() == null) {
                continue;
            }
            for (SeatMapDto.Row row : section.rows()) {
                if (row.seats() == null) {
                    continue;
                }
                for (SeatMapDto.Seat seat : row.seats()) {
                    BigDecimal price = seat.price() != null ? seat.price() : section.price();
                    rows.add(new SeatRow(seat.seatId(), section.name(), row.label(), seat.number(), price));
                }
            }
        }
        if (rows.isEmpty()) {
            throw ApiException.conflict("Event " + eventId + " has no seats to book");
        }
        // Per-row ON CONFLICT DO NOTHING: a lost lock race converges to the same
        // inventory — the second seeder inserts zero rows and both requests proceed.
        jdbcTemplate.batchUpdate(INSERT_SEAT_SQL, rows, 100, (ps, row) -> {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, eventId);
            ps.setString(3, row.seatId());
            ps.setString(4, row.section());
            ps.setString(5, row.rowLabel());
            ps.setInt(6, row.seatNumber());
            ps.setBigDecimal(7, row.price());
            ps.setString(8, SeatStatus.AVAILABLE.name());
        });
        eventNameResolver.cache(eventId, event.name());
        log.info("Seeded inventory ({} seats) for event {}", rows.size(), eventId);
    }
}
