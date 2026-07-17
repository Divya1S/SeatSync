package com.seatsync.notification.service;

/**
 * A message that can never be processed successfully: unparseable JSON,
 * missing required fields, or an unknown event type (§6.5 "poison").
 *
 * <p>Classified NON-retryable in the retry-topic configuration — the record
 * goes straight to the {@code <topic>-dlt} without burning retry attempts.
 * Nothing was claimed and no email was sent when this is thrown.
 */
public class PoisonMessageException extends RuntimeException {

    public PoisonMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
