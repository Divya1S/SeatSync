package com.seatsync.booking.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record has two constructors, so Boot must be told which one binds
 * ({@code @ConstructorBinding}); this proves the YAML keys actually land.
 */
class BookingPropertiesTest {

    @Test
    void bindsAllThreeKeysFromConfiguration() {
        var source = new MapConfigurationPropertySource(Map.of(
                "booking.hold-ttl", "PT2M",
                "booking.offer-ttl", "PT7M",
                "booking.max-active-holds-per-user", "3"));

        BookingProperties bound = new Binder(source)
                .bind("booking", Bindable.of(BookingProperties.class))
                .get();

        assertThat(bound.holdTtl()).isEqualTo(Duration.ofMinutes(2));
        assertThat(bound.offerTtl()).isEqualTo(Duration.ofMinutes(7));
        assertThat(bound.maxActiveHoldsPerUser()).isEqualTo(3);
    }

    @Test
    void missingOrNonPositiveValuesFallBackToDefaults() {
        var source = new MapConfigurationPropertySource(Map.of(
                "booking.max-active-holds-per-user", "0"));

        BookingProperties bound = new Binder(source)
                .bind("booking", Bindable.of(BookingProperties.class))
                .get();

        assertThat(bound.holdTtl()).isEqualTo(BookingProperties.DEFAULT_HOLD_TTL);
        assertThat(bound.offerTtl()).isEqualTo(BookingProperties.DEFAULT_OFFER_TTL);
        assertThat(bound.maxActiveHoldsPerUser()).isEqualTo(BookingProperties.DEFAULT_MAX_ACTIVE_HOLDS_PER_USER);
        assertThat(new BookingProperties(null, null).maxActiveHoldsPerUser())
                .isEqualTo(BookingProperties.DEFAULT_MAX_ACTIVE_HOLDS_PER_USER);
    }
}
