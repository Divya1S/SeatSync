package com.seatsync.booking.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

    public static final String TOPIC_BOOKING_CONFIRMED = "seatsync.booking.confirmed";
    public static final String TOPIC_HOLD_EXPIRED = "seatsync.hold.expired";
    public static final String TOPIC_WAITLIST_OFFERED = "seatsync.waitlist.offered";

    @Bean
    public NewTopic bookingConfirmedTopic() {
        return TopicBuilder.name(TOPIC_BOOKING_CONFIRMED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic holdExpiredTopic() {
        return TopicBuilder.name(TOPIC_HOLD_EXPIRED).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic waitlistOfferedTopic() {
        return TopicBuilder.name(TOPIC_WAITLIST_OFFERED).partitions(3).replicas(1).build();
    }
}
