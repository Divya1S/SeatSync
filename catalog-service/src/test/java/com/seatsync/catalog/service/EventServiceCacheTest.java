package com.seatsync.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsync.catalog.cache.CatalogCache;
import com.seatsync.catalog.domain.Event;
import com.seatsync.catalog.domain.EventCategory;
import com.seatsync.catalog.domain.EventStatus;
import com.seatsync.catalog.domain.PriceTier;
import com.seatsync.catalog.domain.Section;
import com.seatsync.catalog.domain.Venue;
import com.seatsync.catalog.error.NotFoundException;
import com.seatsync.catalog.repository.EventRepository;
import com.seatsync.catalog.repository.VenueRepository;
import com.seatsync.catalog.security.JwtUser;
import com.seatsync.catalog.web.dto.EventResponse;
import com.seatsync.catalog.web.dto.SeatMapResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cache-aside behaviour of the public reads (mocked RedisTemplate): cache hit skips the
 * database, cache miss populates the key with a 600s TTL, and any Redis failure falls back
 * to the database so the service keeps working with Redis down.
 */
@ExtendWith(MockitoExtension.class)
class EventServiceCacheTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private EventRepository events;

    @Mock
    private VenueRepository venues;

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    private EventService service;

    private final UUID eventId = UUID.randomUUID();
    private final UUID organizerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new EventService(events, venues, new CatalogCache(redisTemplate, objectMapper, true));
    }

    @Test
    void cacheHitServesEventWithoutTouchingDatabase() throws Exception {
        EventResponse cached = CatalogMapper.toResponse(publishedEvent());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("catalog:event:" + eventId))
                .thenReturn(objectMapper.writeValueAsString(cached));

        EventResponse result = service.getEvent(eventId, null);

        assertEquals("Friday Night Jazz", result.name());
        assertEquals(cached.totalSeats(), result.totalSeats());
        assertEquals(cached.startsAt(), result.startsAt());
        verifyNoInteractions(events);
    }

    @Test
    void cacheMissLoadsFromDatabaseAndPopulatesCacheWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("catalog:event:" + eventId)).thenReturn(null);
        when(events.findWithDetailsById(eventId)).thenReturn(Optional.of(publishedEvent()));

        EventResponse result = service.getEvent(eventId, null);

        assertEquals("Friday Night Jazz", result.name());
        verify(valueOperations).set(eq("catalog:event:" + eventId), anyString(),
                eq(Duration.ofSeconds(600)));
    }

    @Test
    void redisDownFallsBackToDatabase() {
        when(redisTemplate.opsForValue())
                .thenThrow(new RedisConnectionFailureException("Connection refused"));
        when(events.findWithDetailsById(eventId)).thenReturn(Optional.of(publishedEvent()));

        EventResponse result = service.getEvent(eventId, null);

        assertEquals("Friday Night Jazz", result.name());
        assertEquals(EventStatus.PUBLISHED, result.status());
    }

    @Test
    void draftEventIsNotVisibleAnonymouslyAndIsNeverCached() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("catalog:event:" + eventId)).thenReturn(null);
        Event draft = publishedEvent();
        draft.setStatus(EventStatus.DRAFT);
        when(events.findWithDetailsById(eventId)).thenReturn(Optional.of(draft));

        assertThrows(NotFoundException.class, () -> service.getEvent(eventId, null));

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void seatMapCacheHitServesFromRedis() throws Exception {
        SeatMapResponse cached = CatalogMapper.toSeatMap(publishedEvent());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("catalog:seatmap:" + eventId))
                .thenReturn(objectMapper.writeValueAsString(cached));

        SeatMapResponse result = service.getSeatMap(eventId, null);

        assertEquals(eventId, result.eventId());
        assertEquals("A-1-1", result.sections().get(0).rows().get(0).seats().get(0).seatId());
        verifyNoInteractions(events);
    }

    @Test
    void seatMapCacheMissPopulatesCacheWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("catalog:seatmap:" + eventId)).thenReturn(null);
        when(events.findWithDetailsById(eventId)).thenReturn(Optional.of(publishedEvent()));

        SeatMapResponse result = service.getSeatMap(eventId, null);

        assertEquals(2, result.sections().size());
        verify(valueOperations).set(eq("catalog:seatmap:" + eventId), anyString(),
                eq(Duration.ofSeconds(600)));
    }

    @Test
    void publishEvictsBothCacheKeys() {
        Event event = publishedEvent();
        event.setStatus(EventStatus.DRAFT);
        when(events.findWithDetailsById(eventId)).thenReturn(Optional.of(event));
        when(events.save(event)).thenReturn(event);
        JwtUser owner = new JwtUser(organizerId, "organizer@seatsync.local", "Demo Organizer",
                List.of("ORGANIZER"));

        EventResponse result = service.publish(eventId, owner);

        assertEquals(EventStatus.PUBLISHED, result.status());
        verify(redisTemplate).delete(List.of("catalog:event:" + eventId, "catalog:seatmap:" + eventId));
    }

    private Event publishedEvent() {
        Venue venue = new Venue("The Blue Note Hall", "Austin", "1204 Congress Ave");
        venue.setId(UUID.randomUUID());
        Event event = new Event("Friday Night Jazz", "Live jazz.", EventCategory.CONCERT, venue,
                Instant.parse("2026-07-24T20:00:00Z"), Instant.parse("2026-07-24T23:00:00Z"),
                EventStatus.PUBLISHED, organizerId);
        event.setId(eventId);
        event.addSection(new Section("A", PriceTier.STANDARD, new BigDecimal("49.00"), 2, 3));
        event.addSection(new Section("VIP", PriceTier.VIP, new BigDecimal("120.00"), 1, 2));
        return event;
    }
}
