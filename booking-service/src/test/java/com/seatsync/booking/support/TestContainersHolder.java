package com.seatsync.booking.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton containers shared by all integration tests in this module. Started once
 * per JVM; Testcontainers' Ryuk reaper cleans them up when the JVM exits.
 */
public final class TestContainersHolder {

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    public static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

    static {
        Startables.deepStart(POSTGRES, REDIS, KAFKA).join();
    }

    private TestContainersHolder() {
    }
}
