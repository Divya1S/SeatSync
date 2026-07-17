package com.seatsync.concierge.chat;

/** Raised when an AI-only operation (e.g. reindex) is requested while the AI is offline. */
public class ConciergeOfflineException extends RuntimeException {

    public ConciergeOfflineException() {
        super("The AI concierge is offline (no OpenAI API key configured)");
    }
}
