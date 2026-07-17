package com.seatsync.concierge.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient used by the concierge tools to call catalog/booking. Timeouts are
 * deliberately tight (tools run inside an LLM turn) and configured on THIS
 * client only — the OpenAI client must keep its own, much longer, timeouts.
 */
@Configuration
public class HttpConfig {

    @Bean
    public RestClient conciergeRestClient(RestClient.Builder builder) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(5));
        return builder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
