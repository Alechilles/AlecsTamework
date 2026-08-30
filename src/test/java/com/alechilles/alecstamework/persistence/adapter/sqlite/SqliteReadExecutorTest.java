package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceShutdownResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests for typed, lane-isolated replacement reads. */
class SqliteReadExecutorTest {
    private static final PersistenceReadKind TEST_READ = new PersistenceReadKind("kernel_read_test");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        try (Connection connection = connections.openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE example (id INTEGER PRIMARY KEY)");
        }
    }

    @Test
    void preservesFoundAbsentAndFailedAsDistinctOutcomes() throws Exception {
        try (SqliteReadExecutor reads = new SqliteReadExecutor(connections)) {
            PersistenceReadResult<String> found = await(reads.execute(command(
                    PersistenceReadPriority.GAMEPLAY_CRITICAL,
                    connection -> PersistenceReadResult.found("profile-a", 0)
            )));
            PersistenceReadResult<String> absent = await(reads.execute(command(
                    PersistenceReadPriority.GAMEPLAY_CRITICAL,
                    connection -> PersistenceReadResult.absent()
            )));
            PersistenceReadResult<String> failed = await(reads.execute(command(
                    PersistenceReadPriority.GAMEPLAY_CRITICAL,
                    connection -> {
                        throw new SQLException("[SQLITE_CORRUPT] malformed", null, 11);
                    }
            )));

            assertInstanceOf(PersistenceReadResult.Found.class, found);
            assertInstanceOf(PersistenceReadResult.Absent.class, absent);
            PersistenceReadResult.Failed<String> typedFailure =
                    assertInstanceOf(PersistenceReadResult.Failed.class, failed);
            assertEquals(StorageFailureKind.CORRUPT, typedFailure.failure().kind());
        }
    }

    @Test
    void oneReadCommandKeepsOneSnapshotAcrossConcurrentCommit() throws Exception {
        // Regression for the 2026-08-30 coop lifecycle/profile checkpoint failure.
        try (Connection connection = connections.openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE snapshot_value (value TEXT NOT NULL)");
            statement.execute("INSERT INTO snapshot_value(value) VALUES ('before')");
        }
        CountDownLatch firstSelectCompleted = new CountDownLatch(1);
        CountDownLatch commitCompleted = new CountDownLatch(1);

        try (SqliteReadExecutor reads = new SqliteReadExecutor(connections)) {
            var result = reads.execute(command(
                    PersistenceReadPriority.GAMEPLAY_CRITICAL,
                    connection -> {
                        String first = readSnapshotValue(connection);
                        firstSelectCompleted.countDown();
                        assertTrue(commitCompleted.await(1, TimeUnit.SECONDS));
                        String second = readSnapshotValue(connection);
                        return PersistenceReadResult.found(
                                first + ":" + second, 0
                        );
                    }
            ));
            assertTrue(firstSelectCompleted.await(1, TimeUnit.SECONDS));
            try (Connection connection = connections.openWriterConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "UPDATE snapshot_value SET value = 'after'"
                );
            }
            commitCompleted.countDown();

            PersistenceReadResult.Found<String> found = assertInstanceOf(
                    PersistenceReadResult.Found.class,
                    await(result)
            );
            assertEquals("before:before", found.value());
        }
    }

    @Test
    void diagnosticBacklogCannotStarveGameplayLane() throws Exception {
        SqliteReadExecutorConfiguration configuration =
                new SqliteReadExecutorConfiguration(1, 2, 1, 2, 2_000);
        CountDownLatch diagnosticStarted = new CountDownLatch(1);
        CountDownLatch releaseDiagnostic = new CountDownLatch(1);
        try (SqliteReadExecutor reads = new SqliteReadExecutor(connections, configuration)) {
            var diagnostic = reads.execute(command(
                    PersistenceReadPriority.DIAGNOSTIC,
                    connection -> {
                        diagnosticStarted.countDown();
                        assertTrue(releaseDiagnostic.await(1, TimeUnit.SECONDS));
                        return PersistenceReadResult.found("diagnostic", 0);
                    }
            ));
            assertTrue(diagnosticStarted.await(1, TimeUnit.SECONDS));

            PersistenceReadResult<String> gameplay = await(reads.execute(command(
                    PersistenceReadPriority.GAMEPLAY_CRITICAL,
                    connection -> PersistenceReadResult.found("gameplay", 0)
            )));

            assertEquals("gameplay",
                    assertInstanceOf(PersistenceReadResult.Found.class, gameplay).value());
            releaseDiagnostic.countDown();
            assertInstanceOf(PersistenceReadResult.Found.class, await(diagnostic));
        }
    }

    @Test
    void saturationAndClosedAdmissionAreFailuresNotAbsence() throws Exception {
        SqliteReadExecutorConfiguration configuration =
                new SqliteReadExecutorConfiguration(1, 1, 1, 1, 2_000);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SqliteReadExecutor reads = new SqliteReadExecutor(connections, configuration);
        var active = reads.execute(blockingCommand(started, release));
        assertTrue(started.await(1, TimeUnit.SECONDS));
        var queued = reads.execute(command(
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> PersistenceReadResult.found("queued", 0)
        ));
        PersistenceReadResult<String> saturated = await(reads.execute(command(
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> PersistenceReadResult.found("saturated", 0)
        )));
        PersistenceReadResult.Failed<String> saturationFailure =
                assertInstanceOf(PersistenceReadResult.Failed.class, saturated);
        assertEquals("read_executor_saturated", saturationFailure.failure().code());

        assertEquals(PersistenceShutdownResult.Status.TIMED_OUT,
                reads.shutdown(Duration.ZERO).status());
        PersistenceReadResult<String> closed = await(reads.execute(command(
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> PersistenceReadResult.found("late", 0)
        )));
        assertEquals("read_executor_closed",
                assertInstanceOf(PersistenceReadResult.Failed.class, closed).failure().code());

        release.countDown();
        assertInstanceOf(PersistenceReadResult.Found.class, await(active));
        assertInstanceOf(PersistenceReadResult.Found.class, await(queued));
        PersistenceShutdownResult.Status finalStatus = reads.shutdown(Duration.ofSeconds(1)).status();
        assertTrue(finalStatus == PersistenceShutdownResult.Status.DRAINED
                || finalStatus == PersistenceShutdownResult.Status.ALREADY_CLOSED);
    }

    @Test
    void nullReadContractIsReportedAsDecodeFailure() throws Exception {
        try (SqliteReadExecutor reads = new SqliteReadExecutor(connections)) {
            PersistenceReadResult<String> result = await(reads.execute(command(
                    PersistenceReadPriority.GAMEPLAY_CRITICAL,
                    connection -> null
            )));

            PersistenceReadResult.Failed<String> failure =
                    assertInstanceOf(PersistenceReadResult.Failed.class, result);
            assertEquals(StorageFailureKind.DECODE, failure.failure().kind());
            assertEquals("read_contract_returned_null", failure.failure().code());
        }
    }

    @Test
    void reportsEveryTypedOutcomeWithoutGivingMetricsControlFlow()
            throws Exception {
        AtomicInteger completions = new AtomicInteger();
        PersistenceKernelMetrics metrics = new PersistenceKernelMetrics() {
            @Override
            public void readCompleted(
                    PersistenceReadKind readKind,
                    PersistenceReadResult<?> result
            ) {
                assertEquals(TEST_READ, readKind);
                completions.incrementAndGet();
                throw new IllegalStateException("injected_metrics_failure");
            }
        };
        try (SqliteReadExecutor reads = new SqliteReadExecutor(
                connections,
                SqliteReadExecutorConfiguration.DEFAULT,
                metrics
        )) {
            PersistenceReadResult<String> result = await(reads.execute(command(
                    PersistenceReadPriority.GAMEPLAY_CRITICAL,
                    connection -> PersistenceReadResult.found("safe", 0)
            )));

            assertEquals(
                    "safe",
                    assertInstanceOf(
                            PersistenceReadResult.Found.class, result
                    ).value()
            );
            assertEquals(1, completions.get());
        }
    }

    private SqliteReadCommand<String> blockingCommand(CountDownLatch started, CountDownLatch release) {
        return command(PersistenceReadPriority.GAMEPLAY_CRITICAL, connection -> {
            started.countDown();
            assertTrue(release.await(1, TimeUnit.SECONDS));
            return PersistenceReadResult.found("active", 0);
        });
    }

    private SqliteReadCommand<String> command(PersistenceReadPriority priority,
                                              SqliteReadWork<String> work) {
        return new SqliteReadCommand<>(TEST_READ, priority, work);
    }

    private static String readSnapshotValue(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT value FROM snapshot_value"
             )) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static <T> PersistenceReadResult<T> await(
            java.util.concurrent.CompletionStage<PersistenceReadResult<T>> result
    ) throws Exception {
        return result.toCompletableFuture().get(2, TimeUnit.SECONDS);
    }
}
