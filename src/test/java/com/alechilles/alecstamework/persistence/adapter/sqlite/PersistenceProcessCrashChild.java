package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.kernel.PersistenceCancellation;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationTransition;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Forked JVM that halts without cleanup at one replacement operation boundary. */
final class PersistenceProcessCrashChild {
    static final int HALT_EXIT_CODE = 79;
    static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000001");
    static final OperationKind KIND = new OperationKind("process_crash_test");

    private PersistenceProcessCrashChild() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected boundary, database path, and marker path");
        }
        Boundary boundary = Boundary.valueOf(args[0]);
        Path database = Path.of(args[1]).toAbsolutePath().normalize();
        Path marker = Path.of(args[2]).toAbsolutePath().normalize();
        Files.createDirectories(database.getParent());
        SqliteConnectionFactory connections = new SqliteConnectionFactory(database);
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();

        switch (boundary) {
            case BEFORE_PREPARE_COMMIT -> prepareWithoutCommit(connections, marker);
            case AFTER_PREPARE_BEFORE_LIVE_APPLY -> {
                prepare(connections);
                halt(marker, "prepared");
            }
            case DURING_LIVE_APPLY -> {
                prepare(connections);
                applying(connections);
                halt(marker, "live-partial");
            }
            case AFTER_LIVE_APPLY_BEFORE_DURABLE_COMMIT -> {
                prepare(connections);
                applying(connections);
                halt(marker, "live-applied");
            }
            case COMMIT_ERROR_MAY_HAVE_COMMITTED -> {
                prepare(connections);
                applying(connections);
                durable(connections, true, marker);
            }
            case AFTER_DURABLE_BEFORE_PUBLICATION -> {
                prepare(connections);
                applying(connections);
                durable(connections, false, marker);
                halt(marker, "durable");
            }
            case DURING_PUBLICATION -> {
                prepare(connections);
                applying(connections);
                durable(connections, false, marker);
                halt(marker, "partial");
            }
            case AFTER_PUBLICATION_BEFORE_ACK -> {
                prepare(connections);
                applying(connections);
                durable(connections, false, marker);
                halt(marker, "published");
            }
            case DURING_COMPENSATION -> {
                prepare(connections);
                applying(connections);
                transition(connections, OperationPhase.LIVE_APPLYING,
                        OperationPhase.COMPENSATING, -8_000);
                halt(marker, "compensation-partial");
            }
            case DURING_SHUTDOWN -> shutdownMidTransaction(connections, marker);
        }
    }

    private static void prepareWithoutCommit(SqliteConnectionFactory connections, Path marker)
            throws Exception {
        java.sql.Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        new SqliteOperationStore(connection).prepare(prepared());
        Files.writeString(marker, "prepare-uncommitted");
        Runtime.getRuntime().halt(HALT_EXIT_CODE);
    }

    private static void prepare(SqliteConnectionFactory connections) throws Exception {
        try (java.sql.Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            new SqliteOperationStore(connection).prepare(prepared());
            connection.commit();
        }
    }

    private static PreparedOperation prepared() {
        return new PreparedOperation(
                OPERATION, new IdempotencyKey("process-crash-test"), KIND,
                1, "{\"profileId\":\"" + PROFILE + "\"}", "process_crash",
                LifecycleRevision.INITIAL, List.of(OperationScope.profile(PROFILE)), -10_000
        );
    }

    private static void applying(SqliteConnectionFactory connections) throws Exception {
        transition(connections, OperationPhase.PREPARED, OperationPhase.LIVE_APPLYING, -9_000);
    }

    private static void transition(SqliteConnectionFactory connections,
                                   OperationPhase expected,
                                   OperationPhase next,
                                   long atMs) throws Exception {
        try (java.sql.Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            new SqliteOperationStore(connection).transition(new OperationTransition(
                    OPERATION, expected, next, null, null, null, atMs
            ));
            connection.commit();
        }
    }

    private static void durable(SqliteConnectionFactory connections,
                                boolean simulateCommitError,
                                Path marker) throws Exception {
        java.sql.Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        SqlitePersistenceTransactionContext transaction =
                new SqlitePersistenceTransactionContext(connection);
        transaction.identities().createProfile(new CompanionIdentity(
                PROFILE, "Crash Companion", "role", null, null, "world",
                -10_000, -8_000, -8_000, 0
        ));
        transaction.lifecycles().create(new CompanionLifecycle(
                PROFILE, null, LifecycleState.UNRESOLVED, LifecycleLocation.unresolved(),
                LifecycleRevision.INITIAL, null, -8_000,
                ReconciliationGeneration.INITIAL, null
        ));
        transaction.outbox().append(new ProjectionEventDraft(
                OPERATION, new ProjectionEventType("profile_created"),
                PROFILE.toString(), 0, 1, "{}", -8_000
        ));
        transaction.operations().transition(new OperationTransition(
                OPERATION, OperationPhase.LIVE_APPLYING, OperationPhase.DURABLE,
                null, null, null, -8_000
        ));
        connection.commit();
        if (simulateCommitError) {
            Files.writeString(marker, "commit-returned-error");
            Runtime.getRuntime().halt(HALT_EXIT_CODE);
        }
        connection.close();
    }

    private static void shutdownMidTransaction(
            SqliteConnectionFactory connections,
            Path marker
    ) throws Exception {
        prepare(connections);
        CountDownLatch transactionStarted = new CountDownLatch(1);
        CountDownLatch neverRelease = new CountDownLatch(1);
        SqliteSingleWriter writer = new SqliteSingleWriter(connections);
        writer.submit(new SqliteTransactionCommand<>(
                OPERATION,
                KIND,
                TransactionReplayPolicy.NEVER,
                connection -> {
                    new SqliteOperationStore(connection).transition(new OperationTransition(
                            OPERATION, OperationPhase.PREPARED,
                            OperationPhase.LIVE_APPLYING, null, null, null, -9_000
                    ));
                    Files.writeString(marker, "shutdown-transaction-started");
                    transactionStarted.countDown();
                    neverRelease.await();
                    return null;
                }
        ), PersistenceCancellation.NONE);
        if (!transactionStarted.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Shutdown transaction did not start");
        }
        Thread shutdown = new Thread(
                () -> writer.shutdown(Duration.ofSeconds(30)),
                "process-crash-shutdown"
        );
        shutdown.setDaemon(true);
        shutdown.start();
        Thread.sleep(50);
        Runtime.getRuntime().halt(HALT_EXIT_CODE);
    }

    private static void halt(Path marker, String evidence) throws Exception {
        Files.writeString(marker, evidence);
        Runtime.getRuntime().halt(HALT_EXIT_CODE);
    }

    enum Boundary {
        BEFORE_PREPARE_COMMIT,
        AFTER_PREPARE_BEFORE_LIVE_APPLY,
        DURING_LIVE_APPLY,
        AFTER_LIVE_APPLY_BEFORE_DURABLE_COMMIT,
        COMMIT_ERROR_MAY_HAVE_COMMITTED,
        AFTER_DURABLE_BEFORE_PUBLICATION,
        DURING_PUBLICATION,
        AFTER_PUBLICATION_BEFORE_ACK,
        DURING_COMPENSATION,
        DURING_SHUTDOWN
    }

    record Payload(String profileId) {
    }

    static final class Definition implements OperationDefinition<Payload> {
        @Override
        public OperationKind kind() {
            return KIND;
        }

        @Override
        public int payloadVersion() {
            return 1;
        }

        @Override
        public Class<Payload> payloadType() {
            return Payload.class;
        }

        @Override
        public String encode(Payload payload) {
            return "{\"profileId\":\"" + payload.profileId() + "\"}";
        }

        @Override
        public Payload decode(String payloadJson) {
            if (!payloadJson.contains(PROFILE.toString())) {
                throw new IllegalArgumentException("Unexpected crash-test payload");
            }
            return new Payload(PROFILE.toString());
        }
    }
}
