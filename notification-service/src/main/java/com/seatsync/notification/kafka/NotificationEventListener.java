package com.seatsync.notification.kafka;

import com.seatsync.notification.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the three §6.4 topics as raw strings; the payload's "type" field
 * drives composition.
 *
 * <p>Exceptions deliberately propagate: the retry-topic machinery configured
 * in {@code KafkaRetryTopicConfig} classifies them (transient → non-blocking
 * redelivery via the retry topics; poison → straight to {@code <topic>-dlt})
 * so a failing record never blocks the main topics.
 */
@Component
public class NotificationEventListener {

    public static final String TOPIC_BOOKING_CONFIRMED = "seatsync.booking.confirmed";
    public static final String TOPIC_HOLD_EXPIRED = "seatsync.hold.expired";
    public static final String TOPIC_WAITLIST_OFFERED = "seatsync.waitlist.offered";

    private final NotificationService notificationService;

    public NotificationEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @KafkaListener(
            topics = {TOPIC_BOOKING_CONFIRMED, TOPIC_HOLD_EXPIRED, TOPIC_WAITLIST_OFFERED},
            groupId = "notification-service")
    public void onEvent(String payload) {
        notificationService.process(payload);
    }
}
