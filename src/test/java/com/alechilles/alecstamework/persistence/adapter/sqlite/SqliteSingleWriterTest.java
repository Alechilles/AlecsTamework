package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceCancellation;
import com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.kernel.PersistenceShutdownResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceWriteRejection;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration and fault-boundary tests for one-operation replacement transactions. */
class SqliteSingleWriterTest {
    private static final OperationKind TEST_KIND = new OperationKind("kernel_test");

    @TempDir
    Path tempDir;

    @Test
    void failedOperationRollsBackWithoutCouplingTheNextOperation() throws Exception {
        Fixture fixture = fixture(SqliteWriterConfiguration.DEFAULT, (checkpoint, operationId) -> {
        });
        createTable(fixture.connections);
        try (SqliteSingleWriter writer = fixture.writer) {
            PersistenceTransactionResult<Integer> failed = await(writer.submit(command(connection -> {
                insert(connection, 1);
                throw new IllegalStateException("domain failure");
            })));
            PersistenceTransactionResult<Integer> committed = await(writer.submit(command(connection -> {
                insert(connection, 2);
                return 2;
            })));

            assertInstanceOf(PersistenceTransactionResult.RolledBack.class, failed);
            assertInstanceOf(PersistenceTransactionResult.Committed.class, committed);
        }
        assertEquals(1, countRows(fixture.connections));
        assertEquals(2, onlyValue(fixture.connections));
    }

    @Test
    void busyRetriesOnlyKnownRollbackCommandsDeclaredSafe() throws Exception {
        Fixture fixture = fixture(new SqliteWriterConfiguration(8, 3, 0, 2_000),
                (checkpoint, operationId) -> {
                });
        AtomicInteger safeAttempts = new AtomicInteger();
        AtomicInteger unsafeAttempts = new AtomicInteger();
        try (SqliteSingleWriter writer = fixture.writer) {
            PersistenceTransactionResult<Integer> safe = await(writer.submit(command(
                    TransactionReplayPolicy.SAFE_DATABASE_ONLY,
                    connection -> {
                        if (safeAttempts.incrementAndGet() < 3) {
                            throw new SQLException("[SQLITE_BUSY] database is locked", null, 5);
                        }
                        return 7;
                    }
            )));
            PersistenceTransactionResult<Integer> unsafe = await(writer.submit(command(
                    TransactionReplayPolicy.NEVER,
                    connection -> {
                        unsafeAttempts.incrementAndGet();
                        throw new SQLException("[SQLITE_BUSY] database is locked", null, 5);
                    }
            )));

            assertInstanceOf(PersistenceTransactionResult.Committed.class, safe);
            assertInstanceOf(PersistenceTransactionResult.RolledBack.class, unsafe);
            assertEquals(3, safeAttempts.get());
            assertEquals(1, unsafeAttempts.get());
        }
    }

    @Test
    void commitReturnFailureIsUnknownAndNeverBlindlyRetried() throws Exception {
        OperationId operationId = OperationId.create();
        AtomicInteger workCalls = new AtomicInteger();
        Fixture fixture = fixture(new SqliteWriterConfiguration(8, 3, 0, 2_000),
                (checkpoint, currentOperation) -> {
                    if (checkpoint == PersistenceCheckpoint.COMMIT_RETURNED
                            && operationId.equals(currentOperation)) {
                        throw new SQLException("commit return lost");
                    }
                });
        createTable(fixture.connections);
        try (SqliteSingleWriter writer = fixture.writer) {
            PersistenceTransactionResult<Integer> result = await(writer.submit(new SqliteTransactionCommand<>(
                    operationId,
                    TEST_KIND,
                    TransactionReplayPolicy.SAFE_DATABASE_ONLY,
                    connection -> {
                        workCalls.incrementAndGet();
                        insert(connection, 9);
                        return 9;
                    }
            )));

            PersistenceTransactionResult.Unknown<Integer> unknown =
                    assertInstanceOf(PersistenceTransactionResult.Unknown.class, result);
            assertEquals("sqlite_commit_outcome_unknown", unknown.failure().code());
            assertEquals(1, workCalls.get());
        }
        assertEquals(1, countRows(fixture.connections));
    }

    @Test
    void faultsBeforeCommitRollBackWhileAfterCommitCannotDowngradeSuccess() throws Exception {
        for (PersistenceCheckpoint fault : new PersistenceCheckpoint[]{
                PersistenceCheckpoint.BEFORE_BEGIN,
                PersistenceCheckpoint.AFTER_BEGIN,
                PersistenceCheckpoint.BEFORE_COMMIT
        }) {
            Path database = tempDir.resolve(fault.name() + ".sqlite");
            SqliteConnectionFactory connections = new SqliteConnectionFactory(database);
            createTable(connections);
            try (SqliteSingleWriter writer = writer(connections, (checkpoint, operationId) -> {
                if (checkpoint == fault) {
                    throw new SQLException("injected " + fault);
                }
            })) {
                assertInstanceOf(PersistenceTransactionResult.RolledBack.class,
                        await(writer.submit(command(connection -> {
                            insert(connection, 1);
                            return 1;
                        }))));
            }
            assertEquals(0, countRows(connections), fault.name());
        }

        SqliteConnectionFactory committedConnections =
                new SqliteConnectionFactory(tempDir.resolve("after-commit.sqlite"));
        createTable(committedConnections);
        try (SqliteSingleWriter writer = writer(committedConnections, (checkpoint, operationId) -> {
            if (checkpoint == PersistenceCheckpoint.AFTER_COMMIT) {
                throw new SQLException("observer failed after commit");
            }
        })) {
            assertInstanceOf(PersistenceTransactionResult.Committed.class,
                    await(writer.submit(command(connection -> {
                        insert(connection, 2);
                        return 2;
                    }))));
        }
        assertEquals(1, countRows(committedConnections));
    }

    @Test
    void cancellationIsDistinctBeforeAcceptanceAndIgnoredAfterAcceptance() throws Exception {
        Fixture fixture = fixture(SqliteWriterConfiguration.DEFAULT, (checkpoint, operationId) -> {
        });
        createTable(fixture.connections);
        AtomicBoolean cancelled = new AtomicBoolean(true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (SqliteSingleWriter writer = fixture.writer) {
            SqliteSingleWriter.WriteSubmission<Integer> rejected =
                    writer.submit(command(connection -> 1), cancelled::get);
            assertEquals(SqliteSingleWriter.WriteAcceptance.CANCELLED_BEFORE_ACCEPTANCE,
                    rejected.acceptance());
            PersistenceTransactionResult.Rejected<Integer> rejection =
                    assertInstanceOf(PersistenceTransactionResult.Rejected.class,
                            rejected.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));
            assertEquals(PersistenceWriteRejection.CANCELLED_BEFORE_ACCEPTANCE, rejection.reason());

            cancelled.set(false);
            SqliteSingleWriter.WriteSubmission<Integer> accepted =
                    writer.submit(command(connection -> {
                        started.countDown();
                        assertTrue(release.await(1, TimeUnit.SECONDS));
                        insert(connection, 3);
                        return 3;
                    }), cancelled::get);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            cancelled.set(true);
            release.countDown();
            assertInstanceOf(PersistenceTransactionResult.Committed.class, await(accepted));
        }
        assertEquals(1, countRows(fixture.connections));
    }

    @Test
    void boundedAdmissionAndShutdownDrainAreExplicit() throws Exception {
        Fixture fixture = fixture(new SqliteWriterConfiguration(1, 0, 0, 2_000),
                (checkpoint, operationId) -> {
                });
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SqliteSingleWriter writer = fixture.writer;
        SqliteSingleWriter.WriteSubmission<Integer> active = writer.submit(command(connection -> {
            started.countDown();
            assertTrue(release.await(1, TimeUnit.SECONDS));
            return 1;
        }));
        assertTrue(started.await(1, TimeUnit.SECONDS));
        SqliteSingleWriter.WriteSubmission<Integer> queued = writer.submit(command(connection -> 2));
        SqliteSingleWriter.WriteSubmission<Integer> saturated = writer.submit(command(connection -> 3));

        assertEquals(SqliteSingleWriter.WriteAcceptance.REJECTED, saturated.acceptance());
        PersistenceTransactionResult.Rejected<Integer> rejection =
                assertInstanceOf(PersistenceTransactionResult.Rejected.class,
                        saturated.completion().toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals(PersistenceWriteRejection.SATURATED, rejection.reason());

        Thread releaser = new Thread(release::countDown);
        releaser.start();
        PersistenceShutdownResult shutdown = writer.shutdown(Duration.ofSeconds(2));

        assertEquals(PersistenceShutdownResult.Status.DRAINED, shutdown.status());
        assertInstanceOf(PersistenceTransactionResult.Committed.class, await(active));
        assertInstanceOf(PersistenceTransactionResult.Committed.class, await(queued));
        assertEquals(SqliteSingleWriter.State.CLOSED, writer.state());
        SqliteSingleWriter.WriteSubmission<Integer> late = writer.submit(command(connection -> 4));
        assertEquals(SqliteSingleWriter.WriteAcceptance.REJECTED, late.acceptance());
        assertFalse(releaser.isAlive());
    }

    @Test
    void timedOutShutdownRetainsOwnershipUntilAcceptedWorkFinishes() throws Exception {
        Fixture fixture = fixture(new SqliteWriterConfiguration(2, 0, 0, 2_000),
                (checkpoint, operationId) -> {
                });
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SqliteSingleWriter writer = fixture.writer;
        SqliteSingleWriter.WriteSubmission<Integer> accepted = writer.submit(command(connection -> {
            started.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return 5;
        }));
        assertTrue(started.await(1, TimeUnit.SECONDS));

        PersistenceShutdownResult timedOut = writer.shutdown(Duration.ZERO);

        assertEquals(PersistenceShutdownResult.Status.TIMED_OUT, timedOut.status());
        assertEquals(1, timedOut.outstandingOperations());
        assertEquals(SqliteSingleWriter.State.DRAINING, writer.state());
        PersistenceTransactionResult.Rejected<Integer> late =
                assertInstanceOf(PersistenceTransactionResult.Rejected.class,
                        writer.submit(command(connection -> 6)).completion()
                                .toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals(PersistenceWriteRejection.DRAINING, late.reason());

        release.countDown();
        assertInstanceOf(PersistenceTransactionResult.Committed.class, await(accepted));
        PersistenceShutdownResult.Status finalStatus = writer.shutdown(Duration.ofSeconds(1)).status();
        assertTrue(finalStatus == PersistenceShutdownResult.Status.DRAINED
                || finalStatus == PersistenceShutdownResult.Status.ALREADY_CLOSED);
    }

    private Fixture fixture(SqliteWriterConfiguration configuration,
                            com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpointHook hook) {
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(tempDir.resolve(OperationId.create() + ".sqlite"), 0);
        return new Fixture(connections, new SqliteSingleWriter(
                connections, configuration, hook,
                com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics.NO_OP
        ));
    }

    private SqliteSingleWriter writer(
            SqliteConnectionFactory connections,
            com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpointHook hook
    ) {
        return new SqliteSingleWriter(
                connections,
                new SqliteWriterConfiguration(8, 0, 0, 2_000),
                hook,
                com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics.NO_OP
        );
    }

    private SqliteTransactionCommand<Integer> command(SqliteTransactionWork<Integer> work) {
        return command(TransactionReplayPolicy.NEVER, work);
    }

    private SqliteTransactionCommand<Integer> command(TransactionReplayPolicy replayPolicy,
                                                       SqliteTransactionWork<Integer> work) {
        return new SqliteTransactionCommand<>(OperationId.create(), TEST_KIND, replayPolicy, work);
    }

    private static <T> PersistenceTransactionResult<T> await(
            SqliteSingleWriter.WriteSubmission<T> submission
    ) throws Exception {
        return submission.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private static void createTable(SqliteConnectionFactory connections) throws Exception {
        try (Connection connection = connections.openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS values_table (value INTEGER NOT NULL)");
        }
    }

    private static void insert(Connection connection, int value) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO values_table(value) VALUES (" + value + ")");
        }
    }

    private static int countRows(SqliteConnectionFactory connections) throws Exception {
        try (Connection connection = connections.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM values_table")) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static int onlyValue(SqliteConnectionFactory connections) throws Exception {
        try (Connection connection = connections.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT value FROM values_table")) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private record Fixture(SqliteConnectionFactory connections, SqliteSingleWriter writer) {
    }
}
