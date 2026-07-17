package com.seatsync.concierge.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfidencePolicyTest {

    @Test
    void toolInvocationAlwaysMeansHigh() {
        assertThat(ConfidencePolicy.assess(true, 0.0)).isEqualTo(Confidence.HIGH);
        assertThat(ConfidencePolicy.assess(true, 0.6)).isEqualTo(Confidence.HIGH);
    }

    @Test
    void topScoreAtLeastPoint75IsHigh() {
        assertThat(ConfidencePolicy.assess(false, 0.75)).isEqualTo(Confidence.HIGH);
        assertThat(ConfidencePolicy.assess(false, 0.91)).isEqualTo(Confidence.HIGH);
    }

    @Test
    void topScoreAtLeastPoint55IsMedium() {
        assertThat(ConfidencePolicy.assess(false, 0.55)).isEqualTo(Confidence.MEDIUM);
        assertThat(ConfidencePolicy.assess(false, 0.749)).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void lowScoreWithoutToolIsLow() {
        assertThat(ConfidencePolicy.assess(false, 0.549)).isEqualTo(Confidence.LOW);
        assertThat(ConfidencePolicy.assess(false, 0.0)).isEqualTo(Confidence.LOW);
    }

    @Test
    void lowConfidenceAlwaysEscalates() {
        assertThat(ConfidencePolicy.shouldEscalate(Confidence.LOW, "Here is a confident answer.")).isTrue();
    }

    @Test
    void notSureAnswersEscalateEvenWhenConfidenceIsHigh() {
        assertThat(ConfidencePolicy.shouldEscalate(Confidence.HIGH, "I'm NOT SURE about that one.")).isTrue();
        assertThat(ConfidencePolicy.shouldEscalate(Confidence.MEDIUM,
                "Please contact support@seatsync.local for help.")).isTrue();
    }

    @Test
    void nullAnswerEscalates() {
        assertThat(ConfidencePolicy.shouldEscalate(Confidence.HIGH, null)).isTrue();
    }

    @Test
    void groundedConfidentAnswerDoesNotEscalate() {
        assertThat(ConfidencePolicy.shouldEscalate(Confidence.HIGH,
                "Holds last exactly 5 minutes and are free.")).isFalse();
        assertThat(ConfidencePolicy.shouldEscalate(Confidence.MEDIUM,
                "Refunds are 100% at least 48 hours before start.")).isFalse();
    }
}
