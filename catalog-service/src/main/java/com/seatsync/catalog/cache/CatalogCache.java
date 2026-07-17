package com.seatsync.catalog.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsync.catalog.web.dto.EventResponse;
import com.seatsync.catalog.web.dto.SeatMapResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicit cache-aside for the public event / seat-map views (CONVENTIONS §6.2):
 * keys {@code catalog:event:{id}} and {@code catalog:seatmap:{id}}, TTL 600s, JSON values,
 * both keys deleted on any write to the event. Every Redis error is caught and logged so the
 * service keeps working (straight from the database) when Redis is down.
 */
@Component
public class CatalogCache {

    private static final Logger log = LoggerFactory.getLogger(CatalogCache.class);

    public static final Duration TTL = Duration.ofSeconds(600);
    private static final String EVENT_KEY_PREFIX = "catalog:event:";
    private static final String SEATMAP_KEY_PREFIX = "catalog:seatmap:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public CatalogCache(StringRedisTemplate redisTemplate,
                        ObjectMapper objectMapper,
                        @Value("${catalog.cache.enabled:true}") boolean enabled) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        if (!enabled) {
            log.warn("Catalog cache-aside is DISABLED (catalog.cache.enabled=false) — all reads go to the database");
        }
    }

    public static String eventKey(UUID eventId) {
        return EVENT_KEY_PREFIX + eventId;
    }

    public static String seatMapKey(UUID eventId) {
        return SEATMAP_KEY_PREFIX + eventId;
    }

    public Optional<EventResponse> getEvent(UUID eventId) {
        return read(eventKey(eventId), EventResponse.class);
    }

    public void putEvent(UUID eventId, EventResponse event) {
        write(eventKey(eventId), event);
    }

    public Optional<SeatMapResponse> getSeatMap(UUID eventId) {
        return read(seatMapKey(eventId), SeatMapResponse.class);
    }

    public void putSeatMap(UUID eventId, SeatMapResponse seatMap) {
        write(seatMapKey(eventId), seatMap);
    }

    /** Deletes both cache keys for the event; called on every write/publish/cancel. */
    public void evict(UUID eventId) {
        try {
            redisTemplate.delete(List.of(eventKey(eventId), seatMapKey(eventId)));
        } catch (Exception ex) {
            log.warn("Redis unavailable, could not evict cache for event {}: {}", eventId, ex.getMessage());
        }
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception ex) {
            log.warn("Redis read failed for key {} (falling back to database): {}", key, ex.getMessage());
            return Optional.empty();
        }
    }

    private void write(String key, Object value) {
        if (!enabled) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), TTL);
        } catch (Exception ex) {
            log.warn("Redis write failed for key {}: {}", key, ex.getMessage());
        }
    }
}
