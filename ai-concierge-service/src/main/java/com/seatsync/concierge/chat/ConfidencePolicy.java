package com.seatsync.concierge.chat;

import java.util.Locale;

/**
 * Confidence heuristic per CONVENTIONS §6.6:
 * HIGH if a tool was called or the top RAG similarity score ≥ 0.75;
 * MEDIUM if the top score ≥ 0.55; else LOW ⇒ escalate to a human.
 * Additionally, an answer where the model says it is not sure (or points at
 * support) is always escalated, whatever the retrieval scores were.
 */
public final class ConfidencePolicy {

    public static final double HIGH_SCORE_THRESHOLD = 0.75;
    public static final double MEDIUM_SCORE_THRESHOLD = 0.55;
    public static final String SUPPORT_EMAIL = "support@seatsync.local";

    private ConfidencePolicy() {
    }

    public static Confidence assess(boolean toolInvoked, double topScore) {
        if (toolInvoked || topScore >= HIGH_SCORE_THRESHOLD) {
            return Confidence.HIGH;
        }
        if (topScore >= MEDIUM_SCORE_THRESHOLD) {
            return Confidence.MEDIUM;
        }
        return Confidence.LOW;
    }

    public static boolean shouldEscalate(Confidence confidence, String answer) {
        if (confidence == Confidence.LOW || answer == null) {
            return true;
        }
        String lower = answer.toLowerCase(Locale.ROOT);
        return lower.contains("not sure") || lower.contains(SUPPORT_EMAIL);
    }
}
