package com.seatsync.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Booking TTL knobs (conventions section 8.1). Both the Redis {@code PX} value
 * and the database {@code expires_at} derive from these — they are the single
 * source of truth for how long a hold or a waitlist offer-hold stays alive.
 *
 * <ul>
 *   <li>{@code booking.hold-ttl} (env {@code HOLD_TTL}, default PT5M) — user holds;</li>
 *   <li>{@code booking.offer-ttl} (env {@code OFFER_TTL}, default PT10M) — waitlist offer-holds.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "booking")
public record BookingProperties(Duration holdTtl, Duration offerTtl) {

    public static final Duration DEFAULT_HOLD_TTL = Duration.ofMinutes(5);
    public static final Duration DEFAULT_OFFER_TTL = Duration.ofMinutes(10);

    public BookingProperties {
        holdTtl = holdTtl != null ? holdTtl : DEFAULT_HOLD_TTL;
        offerTtl = offerTtl != null ? offerTtl : DEFAULT_OFFER_TTL;
    }
}
