package com.seatsync.booking.catalog;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * All catalog access goes through this gateway so it can be protected by the
 * "catalog" circuit breaker + retry (3 attempts, 200ms exponential backoff,
 * 4xx responses are ignored by both). Failures propagate to the caller which
 * translates them into RFC 7807 responses (503 when inventory cannot be seeded).
 */
@Service
public class CatalogGateway {

    private final CatalogClient catalogClient;

    public CatalogGateway(CatalogClient catalogClient) {
        this.catalogClient = catalogClient;
    }

    @Retry(name = "catalog")
    @CircuitBreaker(name = "catalog")
    public EventDto getEvent(UUID eventId) {
        return catalogClient.getEvent(eventId);
    }

    @Retry(name = "catalog")
    @CircuitBreaker(name = "catalog")
    public SeatMapDto getSeatMap(UUID eventId) {
        return catalogClient.getSeatMap(eventId);
    }
}
