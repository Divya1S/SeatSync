package com.seatsync.notification.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Terminal handler for records parked in a {@code <topic>-dlt} (§6.5).
 *
 * <p>Only logs (ERROR) — the notifications row, if one was claimed, was
 * already left FAILED by {@code NotificationService}, and DLT records keep
 * the original payload plus the exception headers written by the
 * retry-topic machinery. Replay (see docs/runbook-dlt-replay.md) is safe at
 * any time: re-publishing the payload to the main topic hits the message_id
 * claim, whose FAILED state retries the send on the same row.
 */
@Component
public class NotificationDltHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationDltHandler.class);

    public void handleDlt(ConsumerRecord<String, String> record) {
        log.error("Notification event parked in DLT: dltTopic={} originalTopic={} partition={} offset={} "
                        + "exception={} cause={} exceptionMessage={} payload={}",
                record.topic(),
                stringHeader(record, KafkaHeaders.ORIGINAL_TOPIC),
                intHeader(record, KafkaHeaders.ORIGINAL_PARTITION),
                longHeader(record, KafkaHeaders.ORIGINAL_OFFSET),
                stringHeader(record, KafkaHeaders.EXCEPTION_FQCN),
                stringHeader(record, KafkaHeaders.EXCEPTION_CAUSE_FQCN),
                stringHeader(record, KafkaHeaders.EXCEPTION_MESSAGE),
                abbreviate(record.value()));
    }

    private static String stringHeader(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? "n/a"
                : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Object intHeader(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null || header.value().length != Integer.BYTES
                ? "n/a" : ByteBuffer.wrap(header.value()).getInt();
    }

    private static Object longHeader(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null || header.value().length != Long.BYTES
                ? "n/a" : ByteBuffer.wrap(header.value()).getLong();
    }

    private static String abbreviate(String payload) {
        if (payload == null) {
            return "null";
        }
        return payload.length() <= 500 ? payload : payload.substring(0, 500) + "…";
    }
}
