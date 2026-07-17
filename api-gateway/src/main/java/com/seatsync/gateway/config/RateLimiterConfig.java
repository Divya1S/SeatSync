package com.seatsync.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Per-client-IP rate limiting key (CONVENTIONS §6.7).
 *
 * The RequestRateLimiter filter on the /api/** routes uses
 * RedisRateLimiter(replenishRate=20, burstCapacity=40) with this resolver.
 * Note: RedisRateLimiter FAILS OPEN by default — if Redis is unreachable the
 * Lua script call errors are swallowed and requests are allowed through.
 */
@Configuration
public class RateLimiterConfig {

    /**
     * Resolves the client key as the remote address by default. This gateway
     * is the first hop, so a client-supplied {@code X-Forwarded-For} is
     * attacker-controlled: trusting it lets callers dodge per-IP limits with a
     * random header per request, or drain a victim's bucket by pinning their
     * IP. Only when {@code TRUST_XFF=true} (deployment behind a trusted load
     * balancer that overwrites the header) is the first X-Forwarded-For value
     * used instead.
     */
    @Bean
    public KeyResolver ipKeyResolver(@Value("${gateway.trust-xff:${TRUST_XFF:false}}") boolean trustXff) {
        return exchange -> {
            if (trustXff) {
                String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
                if (forwardedFor != null && !forwardedFor.isBlank()) {
                    return Mono.just(forwardedFor.split(",")[0].trim());
                }
            }
            InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
            return Mono.just(remoteAddress != null ? remoteAddress.getHostString() : "unknown");
        };
    }
}
