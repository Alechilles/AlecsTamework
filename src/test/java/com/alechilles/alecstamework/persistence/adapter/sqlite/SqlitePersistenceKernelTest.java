package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceShutdownResult;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import java.nio.file.Path;
import java.sql.Connection;
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

/** Integration tests for coordinated replacement kernel shutdown and checkpoint order. */
class SqlitePersistenceKernelTest {
    private static final OperationKind KIND = new OperationKind("kernel_shutdown_fixture");
    private static final PersistenceReadKind READBACK =
            new PersistenceReadKind("kernel_shutdown_readback");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        try (Connection connection = connections.openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE evidence (operation_id TEXT PRIMARY KEY)");
        }
    }

    @Test
    void cleanShutdownDrainsWriterThenCheckpointsThenClosesReads() {
        SqlitePersistenceKernel kernel = kernel();

        SqliteKernelShutdownReport report = kernel.shutdown(Duration.ofSeconds(2));

        assertTrue(report.clean());
        assertEquals(SqliteKernelShutdownReport.CheckpointStatus.COMPLETED,
                report.checkpoint().status());
        assertInstanceOf(SqliteCheckpointResult.Completed.class, report.checkpoint().result());
        assertEquals(SqlitePersistenceKernel.State.CLOSED, kernel.state());
    }

    @Test
    void writerTimeoutLeavesReadbackLaneOpenUntilAcceptedWorkCompletes() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SqlitePersistenceKernel kernel = kernel();
        OperationId operationId = OperationId.create();
        SqliteUnitOfWork<Void> unit = new SqliteUnitOfWork<>(
                new SqliteTransactionCommand<>(
                        operationId,
                        KIND,
                        TransactionReplayPolicy.NEVER,
                        connection -> {
                            started.countDown();
                            assertTrue(release.await(2, TimeUnit.SECONDS));
                            try (Statement statement = connection.createStatement()) {
                                statement.execute("INSERT INTO evidence(operation_id) VALUES ('"
                                        + operationId + "')");
                            }
                            return null;
                        }
                ),
                READBACK,
                connection -> PersistenceReadResult.absent()
        );
        var accepted = kernel.execute(unit);
        assertTrue(started.await(1, TimeUnit.SECONDS));

        SqliteKernelShutdownReport timedOut = kernel.shutdown(Duration.ZERO);

        assertEquals(PersistenceShutdownResult.Status.TIMED_OUT, timedOut.writer().status());
        assertEquals(SqliteKernelShutdownReport.CheckpointStatus.SKIPPED_WRITER_ACTIVE,
                timedOut.checkpoint().status());
        assertEquals(SqlitePersistenceKernel.State.DRAINING, kernel.state());
        PersistenceReadResult<String> duringDrain = kernel.read(new SqliteReadCommand<>(
                new PersistenceReadKind("shutdown_readback_probe"),
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> PersistenceReadResult.found("available", 0)
        )).toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals("available",
                assertInstanceOf(PersistenceReadResult.Found.class, duringDrain).value());

        release.countDown();
        accepted.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        SqliteKernelShutdownReport completed = kernel.shutdown(Duration.ofSeconds(2));

        assertTrue(completed.clean());
        assertEquals(SqlitePersistenceKernel.State.CLOSED, kernel.state());
    }

    private SqlitePersistenceKernel kernel() {
        return new SqlitePersistenceKernel(
                connections,
                new SqliteWriterConfiguration(8, 0, 0, 2_000),
                new SqliteReadExecutorConfiguration(1, 8, 1, 4, 2_000),
                (checkpoint, operationId) -> {
                },
                PersistenceKernelMetrics.NO_OP,
                2_000
        );
    }
}
