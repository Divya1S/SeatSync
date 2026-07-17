package com.seatsync.booking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Atomic seat claims in Redis: {@code SET hold:{eventId}:{seatId} <json> NX PX <ttl>}.
 * This is the first line of defense against double-booking; the JPA @Version column
 * on seat_inventory is the final one. TTLs are supplied by the caller and derive
 * from {@code booking.hold-ttl} / {@code booking.offer-ttl}
 * ({@link com.seatsync.booking.config.BookingProperties}).
 */
@Component
public class RedisHoldStore {

    private static final Logger log = LoggerFactory.getLogger(RedisHoldStore.class);

    private final StringRedisTemplate redisTemplate;

    public RedisHoldStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Claims the seat with an explicit TTL ({@code PX}); the same duration must be
     * used to compute the {@code expires_at} persisted alongside the hold row.
     *
     * @return true if the seat was claimed by this call, false if a hold key already exists.
     */
    public boolean tryClaim(UUID eventId, String seatId, UUID holdId, UUID userId, Instant expiresAt,
                            Duration ttl) {
        String value = "{\"holdId\":\"%s\",\"userId\":\"%s\",\"expiresAt\":\"%s\"}"
                .formatted(holdId, userId, expiresAt);
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(key(eventId, seatId), value, ttl);
        return Boolean.TRUE.equals(claimed);
    }

    /**
     * Deletes the hold key. Never throws: a booking confirm or an expiry sweep must
     * not fail because Redis is momentarily unavailable (the key has a TTL anyway).
     */
    public void release(UUID eventId, String seatId) {
        try {
            redisTemplate.delete(key(eventId, seatId));
        } catch (Exception e) {
            log.warn("Could not delete Redis hold key for event {} seat {}: {}", eventId, seatId, e.getMessage());
        }
    }

    static String key(UUID eventId, String seatId) {
        return "hold:" + eventId + ":" + seatId;
    }
}
