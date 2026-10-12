package com.seatsync.booking.service;

import com.seatsync.booking.catalog.CatalogGateway;
import com.seatsync.booking.catalog.EventDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event display-name lookup for booking rows and outbox payloads, in three tiers:
 * a bounded in-memory cache, the {@code event_names} table (written when inventory
 * is seeded, so it survives restarts and catalog outages), and finally a catalog
 * call. When no tier can produce a name the resolver returns
 * {@link #UNKNOWN_EVENT_NAME} rather than an empty string: the event schemas
 * require {@code eventName} to be non-empty, and a confirm must never fail or emit
 * an invalid payload because catalog is unreachable.
 */
@Component
public class EventNameResolver {

    /** Placeholder when no tier can resolve a name; satisfies the schemas' minLength 1. */
    public static final String UNKNOWN_EVENT_NAME = "Unknown event";

    /** The in-memory tier is a bounded convenience; the table is the durable tier. */
    static final int MAX_CACHED_NAMES = 10_000;

    private static final Logger log = LoggerFactory.getLogger(EventNameResolver.class);

    private static final String SELECT_SQL = "SELECT name FROM event_names WHERE event_id = ?";
    private static final String UPSERT_SQL = """
            INSERT INTO event_names (event_id, name, updated_at)
            VALUES (?, ?, now())
            ON CONFLICT (event_id) DO UPDATE SET name = EXCLUDED.name, updated_at = now()
            """;

    private final CatalogGateway catalogGateway;
    private final JdbcTemplate jdbcTemplate;
    private final Map<UUID, String> cache = new ConcurrentHashMap<>();

    public EventNameResolver(CatalogGateway catalogGateway, JdbcTemplate jdbcTemplate) {
        this.catalogGateway = catalogGateway;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Records a known name in memory and durably; blank names are ignored. */
    public void cache(UUID eventId, String name) {
        if (eventId == null || name == null || name.isBlank()) {
            return;
        }
        remember(eventId, name);
        try {
            jdbcTemplate.update(UPSERT_SQL, eventId, name);
        } catch (DataAccessException e) {
            log.warn("Could not persist event name for {}: {}", eventId, e.getMessage());
        }
    }

    /** Never blank: memory, then the table, then catalog, then the placeholder. */
    public String resolve(UUID eventId) {
        String cached = cache.get(eventId);
        if (cached != null) {
            return cached;
        }
        String stored = loadStored(eventId);
        if (stored != null) {
            remember(eventId, stored);
            return stored;
        }
        try {
            EventDto event = catalogGateway.getEvent(eventId);
            if (event != null && event.name() != null && !event.name().isBlank()) {
                cache(eventId, event.name());
                return event.name();
            }
        } catch (Exception e) {
            log.warn("Could not resolve event name for {} from catalog: {}", eventId, e.getMessage());
        }
        return UNKNOWN_EVENT_NAME;
    }

    private String loadStored(UUID eventId) {
        try {
            List<String> names = jdbcTemplate.queryForList(SELECT_SQL, String.class, eventId);
            if (names.isEmpty() || names.get(0) == null || names.get(0).isBlank()) {
                return null;
            }
            return names.get(0);
        } catch (DataAccessException e) {
            log.warn("Could not read stored event name for {}: {}", eventId, e.getMessage());
            return null;
        }
    }

    private void remember(UUID eventId, String name) {
        if (cache.size() >= MAX_CACHED_NAMES) {
            // Crude but bounded: the table is authoritative, so dropping the
            // memory tier only costs one indexed read per event.
            cache.clear();
        }
        cache.put(eventId, name);
    }
}
