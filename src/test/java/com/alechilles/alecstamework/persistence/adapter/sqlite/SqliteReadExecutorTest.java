package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceShutdownResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    private static <T> PersistenceReadResult<T> await(
            java.util.concurrent.CompletionStage<PersistenceReadResult<T>> result
    ) throws Exception {
        return result.toCompletableFuture().get(2, TimeUnit.SECONDS);
    }
}
