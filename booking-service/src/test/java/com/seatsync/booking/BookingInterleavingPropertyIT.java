package com.seatsync.booking;

import com.seatsync.booking.error.ApiException;
import com.seatsync.booking.security.JwtUser;
import com.seatsync.booking.service.BookingService;
import com.seatsync.booking.service.EventNameResolver;
import com.seatsync.booking.service.HoldExpiryService;
import com.seatsync.booking.service.HoldService;
import com.seatsync.booking.support.TestContainersHolder;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based interleaving test (conventions 8.1): random sequences of
 * HOLD / CONFIRM / CANCEL / RELEASE / EXPIRE_SWEEP over a tiny model (1 event,
 * 4 seats, 6 users) run against the REAL service layer with real Postgres,
 * Redis and Kafka (the shared {@link TestContainersHolder} containers, but an
 * isolated {@code seatsync_proptest} database so no other test context's
 * sweeper or relay touches these rows).
 *
 * <p>Integration approach (reported per 8.1): a manually-managed static
 * ApplicationContext booted once for the whole class, instead of
 * jqwik-spring — jqwik re-instantiates the test class for every try, and a
 * plain static context keeps the lifecycle trivial and Boot-version-agnostic.
 *
 * <p>Seeds are FIXED so runs are reproducible and CI-stable; on failure jqwik
 * reports (and shrinks) the offending action sequence — the shrunk
 * {@code Action(type, user, seat)} list is the counterexample to transcribe.
 *
 * <p>After every sequence the three 8.1 invariants are asserted via SQL:
 * (1) no seat has two CONFIRMED bookings, (2) every booking has its
 * BookingConfirmed outbox row (same-transaction guarantee), (3) at most one
 * active hold per seat and total active holds never exceed remaining capacity.
 */
class BookingInterleavingPropertyIT {

    private static final int SEATS = 4;
    private static final int USERS = 6;
    private static final BigDecimal PRICE = new BigDecimal("19.00");

    /** Booted once per JVM; closed by Boot's shutdown hook. */
    private static final ConfigurableApplicationContext CTX = startContext();

    private final HoldService holdService = CTX.getBean(HoldService.class);
    private final BookingService bookingService = CTX.getBean(BookingService.class);
    private final HoldExpiryService holdExpiryService = CTX.getBean(HoldExpiryService.class);
    private final JdbcTemplate jdbc = CTX.getBean(JdbcTemplate.class);

    private static ConfigurableApplicationContext startContext() {
        createDatabase("seatsync_proptest");
        SpringApplication app = new SpringApplication(BookingServiceApplication.class);
        // Command-line args outrank application.yml (setDefaultProperties would lose).
        return app.run(
                "--server.port=0",
                "--spring.datasource.url=jdbc:postgresql://" + TestContainersHolder.POSTGRES.getHost()
                        + ":" + TestContainersHolder.POSTGRES.getMappedPort(5432) + "/seatsync_proptest",
                "--spring.datasource.username=" + TestContainersHolder.POSTGRES.getUsername(),
                "--spring.datasource.password=" + TestContainersHolder.POSTGRES.getPassword(),
                "--spring.data.redis.host=" + TestContainersHolder.REDIS.getHost(),
                "--spring.data.redis.port=" + TestContainersHolder.REDIS.getMappedPort(6379),
                "--spring.kafka.bootstrap-servers=" + TestContainersHolder.KAFKA.getBootstrapServers(),
                "--management.tracing.sampling.probability=0.0",
                "--management.zipkin.tracing.export.enabled=false");
    }

    private static void createDatabase(String name) {
        try (Connection connection = DriverManager.getConnection(
                TestContainersHolder.POSTGRES.getJdbcUrl(),
                TestContainersHolder.POSTGRES.getUsername(),
                TestContainersHolder.POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            // Already exists (same JVM) — fine.
        }
    }

    // ---------------------------------------------------------------- model

    enum ActionType {
        HOLD, CONFIRM, CANCEL, RELEASE, EXPIRE_SWEEP
    }

    /** {@code user}/{@code seat} are indices into the fixed 6-user/4-seat model. */
    record Action(ActionType type, int user, int seat) {
    }

    @Provide
    Arbitrary<List<Action>> actionSequences() {
        Arbitrary<Action> actions = Combinators.combine(
                Arbitraries.of(ActionType.values()),
                Arbitraries.integers().between(0, USERS - 1),
                Arbitraries.integers().between(0, SEATS - 1)
        ).as(Action::new);
        return actions.list().ofMinSize(15).ofMaxSize(40);
    }

    // ----------------------------------------------------------- properties

    @Property(tries = 30, seed = "424242")
    void randomInterleavingsNeverViolateBookingInvariants(
            @ForAll("actionSequences") List<Action> actions) {
        Session session = newSession();
        for (Action action : actions) {
            execute(session, action);
        }
        assertInvariants(session.eventId);
    }

    /**
     * Same model, but the two halves of the sequence run on two threads at
     * once — a genuinely racy interleaving on top of the random ordering.
     */
    @Property(tries = 12, seed = "31337")
    void concurrentlyExecutedHalvesNeverViolateBookingInvariants(
            @ForAll("actionSequences") List<Action> actions) throws Exception {
        Session session = newSession();
        List<Action> first = actions.subList(0, actions.size() / 2);
        List<Action> second = actions.subList(actions.size() / 2, actions.size());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        for (List<Action> half : List.of(first, second)) {
            pool.submit(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    for (Action action : half) {
                        execute(session, action);
                    }
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).as("halves must not hang").isTrue();
        pool.shutdownNow();
        assertThat(unexpected).isEmpty();

        assertInvariants(session.eventId);
    }

    // ------------------------------------------------------------ execution

    private static final class Session {
        final UUID eventId = UUID.randomUUID();
        final JwtUser[] users = new JwtUser[USERS];
        final Map<Integer, Deque<UUID>> activeHolds = new ConcurrentHashMap<>();
        final Map<Integer, Deque<UUID>> confirmedBookings = new ConcurrentHashMap<>();
    }

    private Session newSession() {
        Session session = new Session();
        for (int u = 0; u < USERS; u++) {
            session.users[u] = new JwtUser(UUID.randomUUID(), "prop-user-" + u + "@test.io",
                    "Prop User " + u, List.of("ATTENDEE"));
        }
        for (int n = 1; n <= SEATS; n++) {
            jdbc.update("insert into seat_inventory "
                            + "(id, event_id, seat_id, section, row_label, seat_number, price, status, version) "
                            + "values (?, ?, ?, 'A', '1', ?, ?, 'AVAILABLE', 0)",
                    UUID.randomUUID(), session.eventId, "A-1-" + n, n, PRICE);
        }
        // Keep confirm/expiry off the (dead) catalog: pre-cache the event name.
        CTX.getBean(EventNameResolver.class).cache(session.eventId, "Property Night");
        return session;
    }

    /**
     * Applies one action through the real service layer. Domain rejections
     * (ApiException) and clean concurrency losses (optimistic lock, lock
     * acquisition) are legal outcomes; anything else fails the property.
     */
    private void execute(Session s, Action action) {
        JwtUser user = s.users[action.user()];
        switch (action.type()) {
            case HOLD -> {
                try {
                    HoldService.HoldResult result =
                            holdService.createHold(s.eventId, "A-1-" + (action.seat() + 1), user);
                    Deque<UUID> deque = s.activeHolds
                            .computeIfAbsent(action.user(), k -> new ConcurrentLinkedDeque<>());
                    if (!deque.contains(result.hold().holdId())) {
                        deque.push(result.hold().holdId());
                    }
                } catch (ApiException | ConcurrencyFailureException expected) {
                    // 404/409/...: clean rejection.
                }
            }
            case CONFIRM -> {
                Deque<UUID> deque = s.activeHolds.get(action.user());
                UUID holdId = deque == null ? null : deque.peek();
                if (holdId == null) {
                    return; // user has nothing to confirm — no-op
                }
                try {
                    BookingService.ConfirmResult result = bookingService.confirm(holdId, user);
                    deque.remove(holdId);
                    s.confirmedBookings
                            .computeIfAbsent(action.user(), k -> new ConcurrentLinkedDeque<>())
                            .push(result.booking().bookingId());
                } catch (ApiException | ConcurrencyFailureException expected) {
                    deque.remove(holdId); // expired/lost hold is unusable from here on
                }
            }
            case RELEASE -> {
                Deque<UUID> deque = s.activeHolds.get(action.user());
                UUID holdId = deque == null ? null : deque.peek();
                if (holdId == null) {
                    return;
                }
                try {
                    holdService.releaseHold(holdId, user);
                } catch (ApiException | ConcurrencyFailureException expected) {
                    // already confirmed/expired elsewhere
                }
                deque.remove(holdId);
            }
            case CANCEL -> {
                Deque<UUID> deque = s.confirmedBookings.get(action.user());
                UUID bookingId = deque == null ? null : deque.peek();
                if (bookingId == null) {
                    return;
                }
                try {
                    bookingService.cancel(bookingId, user);
                } catch (ApiException | ConcurrencyFailureException expected) {
                    // already cancelled / concurrent state change
                }
                deque.remove(bookingId);
            }
            case EXPIRE_SWEEP -> {
                // Simulate time passing for THIS user's live holds, then run the
                // real sweeper (the scheduled one may race it: FOR UPDATE SKIP
                // LOCKED makes that safe by design).
                jdbc.update("update holds set expires_at = now() - interval '1 second' "
                                + "where event_id = ? and user_id = ? and status = 'HELD'",
                        s.eventId, user.id());
                holdExpiryService.expireHolds();
                s.activeHolds.remove(action.user());
            }
        }
    }

    // ------------------------------------------------------------ invariants

    private void assertInvariants(UUID eventId) {
        // (1) No seat ever collects two CONFIRMED bookings.
        List<Map<String, Object>> doubleConfirmed = jdbc.queryForList(
                "SELECT seat_id FROM bookings WHERE status='CONFIRMED' "
                        + "GROUP BY event_id, seat_id HAVING count(*)>1");
        assertThat(doubleConfirmed)
                .as("invariant 1: no seat may have two CONFIRMED bookings")
                .isEmpty();

        // (2) Every booking committed together with its BookingConfirmed outbox row.
        Integer orphanBookings = jdbc.queryForObject(
                "select count(*) from bookings b where b.event_id = ? and not exists ("
                        + "  select 1 from outbox_events o"
                        + "  where o.topic = 'seatsync.booking.confirmed'"
                        + "  and o.payload like '%' || b.id::text || '%')",
                Integer.class, eventId);
        assertThat(orphanBookings)
                .as("invariant 2: every booking must have a BookingConfirmed outbox row")
                .isZero();

        // (3) At most one ACTIVE hold per seat; total active holds fit into the
        // remaining capacity; booked seats within capacity and in lockstep with
        // CONFIRMED bookings.
        List<Map<String, Object>> multiHeldSeats = jdbc.queryForList(
                "select seat_id from holds where event_id = ? and status = 'HELD' "
                        + "and expires_at > now() group by seat_id having count(*) > 1",
                eventId);
        assertThat(multiHeldSeats)
                .as("invariant 3a: at most one active hold per seat")
                .isEmpty();
        Integer activeHolds = jdbc.queryForObject(
                "select count(*) from holds where event_id = ? and status = 'HELD' "
                        + "and expires_at > now()",
                Integer.class, eventId);
        Integer available = jdbc.queryForObject(
                "select count(*) from seat_inventory where event_id = ? and status = 'AVAILABLE'",
                Integer.class, eventId);
        Integer booked = jdbc.queryForObject(
                "select count(*) from seat_inventory where event_id = ? and status = 'BOOKED'",
                Integer.class, eventId);
        Integer confirmed = jdbc.queryForObject(
                "select count(*) from bookings where event_id = ? and status = 'CONFIRMED'",
                Integer.class, eventId);
        assertThat(activeHolds)
                .as("invariant 3b: active holds must fit into remaining capacity")
                .isLessThanOrEqualTo(available);
        assertThat(booked).isLessThanOrEqualTo(SEATS);
        assertThat(confirmed)
                .as("CONFIRMED bookings and BOOKED seats must move in lockstep")
                .isEqualTo(booked);
    }
}
