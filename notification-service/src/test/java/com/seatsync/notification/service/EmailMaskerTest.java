package com.seatsync.notification.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailMaskerTest {

    @Test
    void masksTheLocalPartButKeepsTheFirstCharacterAndDomain() {
        assertThat(EmailMasker.mask("attendee@seatsync.local")).isEqualTo("a***@seatsync.local");
        assertThat(EmailMasker.mask("x@y.z")).isEqualTo("x***@y.z");
    }

    @Test
    void handlesMissingOrMalformedAddresses() {
        assertThat(EmailMasker.mask(null)).isEqualTo("<none>");
        assertThat(EmailMasker.mask("  ")).isEqualTo("<none>");
        assertThat(EmailMasker.mask("not-an-email")).isEqualTo("***");
        assertThat(EmailMasker.mask("@nolocal")).isEqualTo("***");
    }

    @Test
    void masksEveryAddressInsideAPayloadExcerpt() {
        String payload = "{\"userEmail\":\"attendee@seatsync.local\",\"cc\":\"ops.team+x@example.org\"}";
        assertThat(EmailMasker.maskAll(payload))
                .isEqualTo("{\"userEmail\":\"a***@seatsync.local\",\"cc\":\"o***@example.org\"}");
        assertThat(EmailMasker.maskAll(null)).isNull();
    }
}
