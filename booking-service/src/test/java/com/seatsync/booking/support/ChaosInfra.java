package com.seatsync.booking.support;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Container stack for the chaos suite (conventions 8.1): Postgres, Redis and
 * Kafka on one Docker network with a Toxiproxy container in front. The Spring
 * app under test connects to EVERY backend exclusively through a Toxiproxy
 * listener, so tests can inject latency or cut a dependency per scenario.
 *
 * <p>Kafka needs special handling: the broker's advertised listener must point
 * back at the HOST-side Toxiproxy endpoint, otherwise clients would learn the
 * broker's direct address from the first metadata response and silently bypass
 * the proxy. {@code withListener(listener, advertisedSupplier)} wires that up.
 *
 * <p>The catalog proxy is a permanently DISABLED listener (a blackholed
 * upstream): pointing {@code catalog.base-url} at it simulates a network
 * partition between booking and catalog.
 *
 * <p>Started once per JVM (like {@link TestContainersHolder}); Ryuk reaps the
 * containers on JVM exit.
 */
public final class ChaosInfra {

    public static final int POSTGRES_PROXY_PORT = 8666;
    public static final int REDIS_PROXY_PORT = 8667;
    public static final int KAFKA_PROXY_PORT = 8668;
    public static final int CATALOG_PROXY_PORT = 8669;

    private static final int KAFKA_INTERNAL_LISTENER_PORT = 19092;

    public static final Network NETWORK = Network.newNetwork();

    public static final ToxiproxyContainer TOXIPROXY =
            new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("toxiproxy");

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("chaos-postgres");

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withNetwork(NETWORK)
                    .withNetworkAliases("chaos-redis");

    public static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("chaos-kafka")
                    // Extra broker listener bound in-network; ADVERTISED as the
                    // host-side Toxiproxy endpoint so every producer/consumer
                    // connection (bootstrap AND post-metadata) goes through the proxy.
                    .withListener("chaos-kafka:" + KAFKA_INTERNAL_LISTENER_PORT, ChaosInfra::kafkaProxyBootstrap);

    public static final Proxy POSTGRES_PROXY;
    public static final Proxy REDIS_PROXY;
    public static final Proxy KAFKA_PROXY;
    public static final Proxy CATALOG_PROXY;

    static {
        try {
            TOXIPROXY.start();
            ToxiproxyClient client = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
            POSTGRES_PROXY = client.createProxy("postgres", "0.0.0.0:" + POSTGRES_PROXY_PORT,
                    "chaos-postgres:5432");
            REDIS_PROXY = client.createProxy("redis", "0.0.0.0:" + REDIS_PROXY_PORT,
                    "chaos-redis:6379");
            KAFKA_PROXY = client.createProxy("kafka", "0.0.0.0:" + KAFKA_PROXY_PORT,
                    "chaos-kafka:" + KAFKA_INTERNAL_LISTENER_PORT);
            // Blackholed catalog: the upstream is never reachable and the listener
            // stays disabled, so connections are refused instantly.
            CATALOG_PROXY = client.createProxy("catalog", "0.0.0.0:" + CATALOG_PROXY_PORT, "localhost:1");
            CATALOG_PROXY.disable();
            Startables.deepStart(POSTGRES, REDIS).join();
            // Kafka MUST start on this thread: its containerIsStarting hook invokes
            // the advertised-listener supplier (a static method of this class), and
            // from any OTHER thread that call would block on this class's still-
            // running static initializer — a classic clinit deadlock. Same-thread
            // reentrant initialization is legal, so a plain start() here is safe.
            KAFKA.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ChaosInfra() {
    }

    /** JDBC URL of the given database THROUGH the Postgres proxy. */
    public static String proxiedJdbcUrl(String database) {
        return "jdbc:postgresql://" + TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(POSTGRES_PROXY_PORT)
                + "/" + database;
    }

    public static String redisProxyHost() {
        return TOXIPROXY.getHost();
    }

    public static int redisProxyPort() {
        return TOXIPROXY.getMappedPort(REDIS_PROXY_PORT);
    }

    /** Host-side Kafka endpoint (proxy listener) — also the broker's advertised address. */
    public static String kafkaProxyBootstrap() {
        return TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(KAFKA_PROXY_PORT);
    }

    /** Blackholed catalog base URL (disabled proxy listener: instant connection refusal). */
    public static String blackholedCatalogUrl() {
        return "http://" + TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(CATALOG_PROXY_PORT);
    }

    /** Creates an isolated database on the chaos Postgres (direct, proxy-independent). */
    public static void createDatabase(String name) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            // Already exists (context re-created within the same JVM) — fine.
        }
    }

    /** Re-enables pg/redis/kafka proxies and strips all toxics; catalog stays blackholed. */
    public static void healEverything() {
        for (Proxy proxy : new Proxy[]{POSTGRES_PROXY, REDIS_PROXY, KAFKA_PROXY}) {
            try {
                for (Toxic toxic : proxy.toxics().getAll()) {
                    toxic.remove();
                }
                proxy.enable();
            } catch (IOException e) {
                throw new IllegalStateException("Could not heal proxy " + proxy.getName(), e);
            }
        }
    }
}
