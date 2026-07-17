package com.seatsync.booking.config;

import com.seatsync.booking.service.SeatEventBroadcaster;
import com.seatsync.booking.service.SeatEventRelay;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Subscribes every instance to the WS fanout channel (review #6): seat state
 * broadcasts are published to Redis and relayed to each instance's local
 * simple broker, so clients get exactly one copy under N replicas.
 */
@Configuration
public class RedisPubSubConfig {

    @Bean
    public RedisMessageListenerContainer seatEventListenerContainer(RedisConnectionFactory connectionFactory,
                                                                    SeatEventRelay seatEventRelay) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(seatEventRelay, new ChannelTopic(SeatEventBroadcaster.CHANNEL));
        return container;
    }
}
