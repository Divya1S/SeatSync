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
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Context-load + edge behavior test. No downstream services and no Redis are
 * required: the RedisRateLimiter bean is replaced with an always-allow stub so
 * the RequestRateLimiter filters on the /api/** routes never touch Redis
 * (in production the real RedisRateLimiter fails open when Redis is down).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.tracing.sampling.probability=0.0",
                // no Redis server in tests: keep the health endpoint UP
                "management.health.redis.enabled=false"
        })
class ApiGatewayIntegrationTest {

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
    void protectedPathWithoutTokenReturns401ProblemDetail() {
        webTestClient.get().uri("/api/bookings/mine")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Unauthorized")
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.detail").isNotEmpty()
                .jsonPath("$.instance").isEqualTo("/api/bookings/mine");
    }

    @Test
    void protectedAuthPathWithoutTokenReturns401() {
        webTestClient.get().uri("/api/auth/me")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.instance").isEqualTo("/api/auth/me");
    }

    @Test
    void corsPreflightReturnsConfiguredHeaders() {
        webTestClient.options().uri("/api/catalog/events")
                .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600");
    }

    @Test
    void actuatorHealthIsPublic() {
        webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isNotEmpty();
    }
}
