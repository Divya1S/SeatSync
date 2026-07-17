package com.seatsync.notification.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the consumed topics identically to booking-service (CONVENTIONS
 * §6.4: 3 partitions, RF 1). Topic creation is idempotent, so whichever
 * service reaches the broker first creates them with the correct partition
 * count — without this, a pristine-broker startup race (consumer subscribes
 * before the producer's KafkaAdmin runs) either fails the subscription
 * (auto-create off) or creates 1-partition topics (auto-create on), stranding
 * keyed messages outside the consumer's initial assignment.
 */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    public NewTopic bookingConfirmedTopic() {
        return TopicBuilder.name("seatsync.booking.confirmed").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic holdExpiredTopic() {
        return TopicBuilder.name("seatsync.hold.expired").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic waitlistOfferedTopic() {
        return TopicBuilder.name("seatsync.waitlist.offered").partitions(3).replicas(1).build();
    }
}
