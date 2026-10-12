package com.seatsync.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * {@code CORS_ALLOWED_ORIGINS} is a comma-separated list that must bind to the
 * gateway's allowed-origins; an origin outside the list gets no CORS headers.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "CORS_ALLOWED_ORIGINS=http://app.one.test,http://app.two.test",
                "management.tracing.sampling.probability=0.0",
                "management.health.redis.enabled=false"
        })
class CorsOriginsConfigTest {

    @Autowired
    private WebTestClient webTestClient;

    @TestConfiguration
    static class AlwaysAllowRateLimiterConfig {

        @Bean
        @Primary
        RedisRateLimiter alwaysAllowRateLimiter(
                ReactiveStringRedisTemplate redisTemplate,
                @Qualifier(RedisRateLimiter.REDIS_SCRIPT_NAME) RedisScript<List<Long>> script,
                ConfigurationService configurationService) {
            return new RedisRateLimiter(redisTemplate, script, configurationService) {
                @Override
                public Mono<Response> isAllowed(String routeId, String id) {
                    return Mono.just(new Response(true, Map.of()));
                }
            };
        }
    }

    @Test
    void eachConfiguredOriginIsAllowed() {
        for (String origin : List.of("http://app.one.test", "http://app.two.test")) {
            webTestClient.options().uri("/api/catalog/events")
                    .header(HttpHeaders.ORIGIN, origin)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        }
    }

    @Test
    void anOriginOutsideTheListIsRefused() {
        webTestClient.options().uri("/api/catalog/events")
                .header(HttpHeaders.ORIGIN, "http://evil.test")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }
}
