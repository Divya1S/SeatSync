package com.seatsync.notification.config;

import com.seatsync.notification.kafka.NotificationDltHandler;
import com.seatsync.notification.kafka.NotificationEventListener;
import com.seatsync.notification.service.PoisonMessageException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;
import org.springframework.kafka.support.EndpointHandlerMethod;
import org.springframework.mail.MailException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.List;

/**
 * Non-blocking retries + DLT (§6.5) for the notification listener, configured
 * centrally as a {@link RetryTopicConfiguration} bean (spring-kafka picks it
 * up for every listener whose topics match {@code includeTopics}; the
 * retry-topic machinery installs its own error handler on those containers,
 * which is why no separate {@code DefaultErrorHandler} bean exists anymore —
 * one would silently lose to this configuration and never see the records).
 *
 * <p>Semantics: 4 total attempts, exponential backoff 1s ×2 (capped at 8s),
 * retry suffix {@code -retry} with the SINGLE_TOPIC same-interval reuse
 * strategy, DLT suffix {@code -dlt}, retry/DLT topics auto-created with
 * 3 partitions / RF 1 to match §6.4. Note: spring-kafka materializes one
 * retry topic per DISTINCT backoff interval (the delay is a per-topic
 * property), so exponential 1s/2s/4s yields {@code <topic>-retry-1000/-2000/
 * -4000}; SINGLE_TOPIC merges only same-interval attempts — a literal single
 * {@code <topic>-retry} would require a fixed delay.
 *
 * <p>Classification ({@code retryOn} allowlist, causes traversed):
 * transient = mail transport ({@link MailException}) and DB unavailability
 * ({@link TransientDataAccessException}, {@link DataAccessResourceFailureException},
 * {@link CannotCreateTransactionException}) → retried; everything else —
 * {@link PoisonMessageException}, payload-validation errors, unexpected bugs
 * — is treated as non-retryable and goes straight to the DLT, where the
 * payload stays replayable.
 */
@Configuration
public class KafkaRetryTopicConfig {

    @Bean
    public RetryTopicConfiguration notificationRetryTopicConfiguration(
            KafkaTemplate<String, String> kafkaTemplate) {
        return RetryTopicConfigurationBuilder.newInstance()
                .includeTopics(List.of(
                        NotificationEventListener.TOPIC_BOOKING_CONFIRMED,
                        NotificationEventListener.TOPIC_HOLD_EXPIRED,
                        NotificationEventListener.TOPIC_WAITLIST_OFFERED))
                .maxAttempts(4)
                .exponentialBackoff(1_000L, 2.0, 8_000L)
                .useSingleTopicForSameIntervals()
                .retryTopicSuffix("-retry")
                .dltSuffix("-dlt")
                .autoCreateTopicsWith(3, (short) 1)
                .retryOn(List.of(
                        MailException.class,
                        TransientDataAccessException.class,
                        DataAccessResourceFailureException.class,
                        CannotCreateTransactionException.class))
                .traversingCauses()
                .dltHandlerMethod(new EndpointHandlerMethod(NotificationDltHandler.class, "handleDlt"))
                .create(kafkaTemplate);
    }
}
