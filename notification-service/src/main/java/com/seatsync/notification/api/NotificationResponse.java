package com.seatsync.notification.api;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO returned by {@code GET /api/notifications/recent}. Deliberately free of
 * any dependency on the JPA entity (§8.1 rule 2 — DTOs only at the edge);
 * mapping lives in {@code NotificationQueryService}.
 */
public record NotificationResponse(UUID id, String type, String recipient, String subject,
                                   String body, UUID eventId, String seatId, String status,
                                   Instant createdAt) {
}
