package com.seatsync.concierge.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusIngestionServiceTest {

    @Test
    void derivesHumanTitlesFromFilenames() {
        assertThat(CorpusIngestionService.humanTitle("refund-and-cancellation-policy.md"))
                .isEqualTo("Refund and Cancellation Policy");
        assertThat(CorpusIngestionService.humanTitle("holds-and-booking-rules.md"))
                .isEqualTo("Holds and Booking Rules");
        assertThat(CorpusIngestionService.humanTitle("accessibility-and-venue-info.md"))
                .isEqualTo("Accessibility and Venue Info");
        assertThat(CorpusIngestionService.humanTitle("organizer-guide.md"))
                .isEqualTo("Organizer Guide");
        assertThat(CorpusIngestionService.humanTitle("attendee-faq.md"))
                .isEqualTo("Attendee FAQ");
    }

    @Test
    void blankFilenameFallsBackToGenericTitle() {
        assertThat(CorpusIngestionService.humanTitle(null)).isEqualTo("SeatSync documentation");
        assertThat(CorpusIngestionService.humanTitle("")).isEqualTo("SeatSync documentation");
    }
}
