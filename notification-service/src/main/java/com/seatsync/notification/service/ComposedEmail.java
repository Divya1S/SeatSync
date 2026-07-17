package com.seatsync.notification.service;

import java.util.UUID;

/**
 * A fully composed notification email plus the key fields persisted alongside it.
 */
public record ComposedEmail(String type, String recipient, String subject, String body,
                            UUID eventId, String seatId) {
}
