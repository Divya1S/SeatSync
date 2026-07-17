package com.seatsync.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;

class IpKeyResolverTest {

    private final KeyResolver untrusted = new RateLimiterConfig().ipKeyResolver(false);
    private final KeyResolver trusted = new RateLimiterConfig().ipKeyResolver(true);

    @Test
    void ignoresClientSuppliedXffByDefault() {
        // A spoofed X-Forwarded-For must NOT let callers pick their own bucket.
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/catalog/events")
                        .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                        .remoteAddress(new InetSocketAddress("192.168.1.50", 55555))
                        .build());
        StepVerifier.create(untrusted.resolve(exchange))
                .expectNext("192.168.1.50")
                .verifyComplete();
    }

    @Test
    void usesFirstXForwardedForValueWhenTrusted() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/catalog/events")
                        .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                        .remoteAddress(new InetSocketAddress("192.168.1.50", 55555))
                        .build());
        StepVerifier.create(trusted.resolve(exchange))
                .expectNext("203.0.113.7")
                .verifyComplete();
    }

    @Test
    void trustedModeFallsBackToRemoteAddressWithoutHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/catalog/events")
                        .remoteAddress(new InetSocketAddress("192.168.1.50", 55555))
                        .build());
        StepVerifier.create(trusted.resolve(exchange))
                .expectNext("192.168.1.50")
                .verifyComplete();
    }

    @Test
    void fallsBackToRemoteAddress() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/catalog/events")
                        .remoteAddress(new InetSocketAddress("192.168.1.50", 55555))
                        .build());
        StepVerifier.create(untrusted.resolve(exchange))
                .expectNext("192.168.1.50")
                .verifyComplete();
    }
}
