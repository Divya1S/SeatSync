package com.seatsync.concierge.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiAvailabilityTest {

    @Test
    void missingKeyDisablesAi() {
        assertThat(AiAvailability.fromApiKey(null).enabled()).isFalse();
    }

    @Test
    void emptyOrBlankKeyDisablesAi() {
        assertThat(AiAvailability.fromApiKey("").enabled()).isFalse();
        assertThat(AiAvailability.fromApiKey("   ").enabled()).isFalse();
    }

    @Test
    void offlinePlaceholderKeyDisablesAi() {
        assertThat(AiAvailability.fromApiKey("sk-dummy-offline").enabled()).isFalse();
        assertThat(AiAvailability.fromApiKey("  sk-dummy-offline  ").enabled()).isFalse();
    }

    @Test
    void realKeyEnablesAi() {
        assertThat(AiAvailability.fromApiKey("sk-proj-real-key-123").enabled()).isTrue();
    }
}
