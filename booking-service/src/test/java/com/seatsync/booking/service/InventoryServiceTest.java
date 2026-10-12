package com.seatsync.booking.service;

import com.seatsync.booking.catalog.CatalogGateway;
import com.seatsync.booking.catalog.EventDto;
import com.seatsync.booking.catalog.SeatMapDto;
import com.seatsync.booking.error.ApiException;
import com.seatsync.booking.repo.SeatInventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * First-touch seeding: the Redisson lock is only a stampede damper, so a Redis
 * outage must not block seeding; catalog failures degrade to 503 with nothing
 * inserted; the batch insert and the durable name run inside one transaction.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private SeatInventoryRepository seatInventoryRepository;
    @Mock
    private CatalogGateway catalogGateway;
    @Mock
    private EventNameResolver eventNameResolver;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PlatformTransactionManager transactionManager;

    private InventoryService inventoryService;

    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(seatInventoryRepository, catalogGateway, eventNameResolver,
                redissonClient, jdbcTemplate, transactionManager);
        when(seatInventoryRepository.existsByEventId(eventId)).thenReturn(false);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
    }

    @Test
    void seedsWithoutTheLockWhenRedisIsUnreachable() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new RedisConnectionException("redis down"));
        when(catalogGateway.getEvent(eventId)).thenReturn(new EventDto(eventId, "Jazz", "PUBLISHED"));
        when(catalogGateway.getSeatMap(eventId)).thenReturn(seatMap(2));

        inventoryService.ensureSeeded(eventId);

        verify(jdbcTemplate).batchUpdate(contains("INSERT INTO seat_inventory"), anyCollection(), eq(100), any());
        verify(eventNameResolver).cache(eventId, "Jazz");
        verify(transactionManager).commit(any());
        verify(lock, never()).unlock();
    }

    @Test
    void catalogOutageYields503AndInsertsNothing() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(catalogGateway.getEvent(eventId)).thenThrow(new IllegalStateException("connection refused"));

        assertThatThrownBy(() -> inventoryService.ensureSeeded(eventId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        verify(jdbcTemplate, never()).batchUpdate(anyString(), anyCollection(), eq(100), any());
        verify(lock).unlock();
    }

    @Test
    void unpublishedEventYields409() throws Exception {
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(catalogGateway.getEvent(eventId)).thenReturn(new EventDto(eventId, "Draft", "DRAFT"));
        when(catalogGateway.getSeatMap(eventId)).thenReturn(seatMap(1));

        assertThatThrownBy(() -> inventoryService.ensureSeeded(eventId))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(jdbcTemplate, never()).batchUpdate(anyString(), anyCollection(), eq(100), any());
    }

    private SeatMapDto seatMap(int seats) {
        List<SeatMapDto.Seat> row = new java.util.ArrayList<>();
        for (int n = 1; n <= seats; n++) {
            row.add(new SeatMapDto.Seat("A-1-" + n, n, new BigDecimal("20.00")));
        }
        return new SeatMapDto(eventId, List.of(new SeatMapDto.Section("A", "STANDARD", new BigDecimal("20.00"),
                List.of(new SeatMapDto.Row("1", row)))));
    }
}
