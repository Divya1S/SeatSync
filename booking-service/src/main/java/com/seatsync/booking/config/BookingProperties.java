package com.seatsync.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * Booking knobs (conventions section 8.1). Both the Redis {@code PX} value and
 * the database {@code expires_at} derive from the TTLs; they are the single
 * source of truth for how long a hold or a waitlist offer-hold stays alive.
 *
 * <ul>
 *   <li>{@code booking.hold-ttl} (env {@code HOLD_TTL}, default PT5M): user holds;</li>
 *   <li>{@code booking.offer-ttl} (env {@code OFFER_TTL}, default PT10M): waitlist offer-holds;</li>
 *   <li>{@code booking.max-active-holds-per-user} (env {@code MAX_ACTIVE_HOLDS_PER_USER},
 *       default 10): active holds one user may have on one event, so a single
 *       account cannot hoard a sale's inventory.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "booking")
public record BookingProperties(Duration holdTtl, Duration offerTtl, Integer maxActiveHoldsPerUser) {

    public static final Duration DEFAULT_HOLD_TTL = Duration.ofMinutes(5);
    public static final Duration DEFAULT_OFFER_TTL = Duration.ofMinutes(10);
    public static final int DEFAULT_MAX_ACTIVE_HOLDS_PER_USER = 10;

    @ConstructorBinding
    public BookingProperties {
        holdTtl = holdTtl != null ? holdTtl : DEFAULT_HOLD_TTL;
        offerTtl = offerTtl != null ? offerTtl : DEFAULT_OFFER_TTL;
        maxActiveHoldsPerUser = maxActiveHoldsPerUser != null && maxActiveHoldsPerUser > 0
                ? maxActiveHoldsPerUser : DEFAULT_MAX_ACTIVE_HOLDS_PER_USER;
    }

    /** TTL-only form for callers that keep the default hold cap. */
    public BookingProperties(Duration holdTtl, Duration offerTtl) {
        this(holdTtl, offerTtl, null);
    }
}
