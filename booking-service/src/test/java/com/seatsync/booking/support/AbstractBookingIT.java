package com.seatsync.booking.support;

import com.seatsync.booking.catalog.CatalogGateway;
import com.seatsync.booking.catalog.EventDto;
import com.seatsync.booking.catalog.SeatMapDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared Spring context for full-stack integration tests: real HTTP (random port),
 * real Postgres, real Redis, real Kafka. The catalog service is the only stubbed
 * collaborator ({@link MockitoBean} CatalogGateway).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractBookingIT {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = TestContainersHolder.POSTGRES;

    @ServiceConnection
    protected static final KafkaContainer KAFKA = TestContainersHolder.KAFKA;

    protected static final GenericContainer<?> REDIS = TestContainersHolder.REDIS;

    @DynamicPropertySource
    static void bookingProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
        registry.add("management.tracing.sampling.probability", () -> "0.0");
        registry.add("management.zipkin.tracing.export.enabled", () -> "false");
    }

    @MockitoBean
    protected CatalogGateway catalogGateway;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    protected ResponseEntity<Map<String, Object>> postJson(String path, Object body, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearerToken);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }

    /** One section "A", one row "1", seats 1..count -> seat ids A-1-1 .. A-1-count. */
    protected static SeatMapDto seatMapOf(UUID eventId, int count, BigDecimal price) {
        List<SeatMapDto.Seat> seats = new ArrayList<>();
        for (int n = 1; n <= count; n++) {
            seats.add(new SeatMapDto.Seat("A-1-" + n, n, price));
        }
        SeatMapDto.Row row = new SeatMapDto.Row("1", seats);
        SeatMapDto.Section section = new SeatMapDto.Section("A", "STANDARD", price, List.of(row));
        return new SeatMapDto(eventId, List.of(section));
    }

    protected static EventDto publishedEvent(UUID eventId, String name) {
        return new EventDto(eventId, name, "PUBLISHED");
    }
}
