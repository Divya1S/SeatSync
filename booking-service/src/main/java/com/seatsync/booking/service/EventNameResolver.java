package com.seatsync.booking.service;

import com.seatsync.booking.catalog.CatalogGateway;
import com.seatsync.booking.catalog.EventDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best-effort event name lookup for booking rows and Kafka payloads. The cache is
 * populated when inventory is seeded; on a miss we ask catalog once, and if catalog
 * is unreachable we degrade to an empty name rather than failing the operation.
 */
@Component
public class EventNameResolver {

    private static final Logger log = LoggerFactory.getLogger(EventNameResolver.class);

    private final CatalogGateway catalogGateway;
    private final Map<UUID, String> cache = new ConcurrentHashMap<>();

    public EventNameResolver(CatalogGateway catalogGateway) {
        this.catalogGateway = catalogGateway;
    }

    public void cache(UUID eventId, String name) {
        if (name != null) {
            cache.put(eventId, name);
        }
    }

    public String resolve(UUID eventId) {
        String cached = cache.get(eventId);
        if (cached != null) {
            return cached;
        }
        try {
            EventDto event = catalogGateway.getEvent(eventId);
            if (event != null && event.name() != null) {
                cache.put(eventId, event.name());
                return event.name();
            }
        } catch (Exception e) {
            log.warn("Could not resolve event name for {}: {}", eventId, e.getMessage());
        }
        return "";
    }
}
