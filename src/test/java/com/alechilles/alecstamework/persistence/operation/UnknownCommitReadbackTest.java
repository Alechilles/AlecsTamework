package com.alechilles.alecstamework.persistence.operation;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteReadExecutor;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSingleWriter;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteTransactionCommand;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteUnitOfWork;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteUnitOfWorkRunner;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteWriterConfiguration;
import com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Replacement evidence that an unknown commit is read back exactly before any retry. */
class UnknownCommitReadbackTest {
    private static final OperationKind KIND = new OperationKind("unknown_commit_fixture");
    private static final PersistenceReadKind READBACK =
            new PersistenceReadKind("unknown_commit_fixture_readback");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        try (Connection connection = connections.openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE operation_evidence (
                        operation_id TEXT PRIMARY KEY,
                        result_value INTEGER NOT NULL
                    )
                    """);
        }
    }

    @Test
    void committedEvidenceResolvesLostCommitReturnWithoutReplayingWork() throws Exception {
        OperationId operationId = OperationId.create();
        AtomicInteger workCalls = new AtomicInteger();
        AtomicInteger readbackCalls = new AtomicInteger();
        try (SqliteSingleWriter writer = writer(operationId);
             SqliteReadExecutor reads = new SqliteReadExecutor(connections)) {
            SqliteUnitOfWorkRunner runner = new SqliteUnitOfWorkRunner(writer, reads);
            SqliteUnitOfWork<Integer> unit = unit(
                    operationId,
                    connection -> {
                        workCalls.incrementAndGet();
                        insertEvidence(connection, operationId, 17);
                        return 17;
                    },
                    connection -> {
                        readbackCalls.incrementAndGet();
                        return loadEvidence(connection, operationId);
                    }
            );

            PersistenceTransactionResult<Integer> result =
                    runner.execute(unit).completion().toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(17,
                    assertInstanceOf(PersistenceTransactionResult.Committed.class, result).value());
            assertEquals(1, workCalls.get());
            assertEquals(1, readbackCalls.get());
        }
    }

    @Test
    void failedReadbackPreservesUnknownOutcomeAndStillDoesNotReplay() throws Exception {
        OperationId operationId = OperationId.create();
        AtomicInteger workCalls = new AtomicInteger();
        try (SqliteSingleWriter writer = writer(operationId);
             SqliteReadExecutor reads = new SqliteReadExecutor(connections)) {
            SqliteUnitOfWorkRunner runner = new SqliteUnitOfWorkRunner(writer, reads);
            SqliteUnitOfWork<Integer> unit = unit(
                    operationId,
                    connection -> {
                        workCalls.incrementAndGet();
                        insertEvidence(connection, operationId, 23);
                        return 23;
                    },
                    connection -> {
                        throw new SQLException("[SQLITE_CORRUPT] readback unavailable", null, 11);
                    }
            );

            PersistenceTransactionResult<Integer> result =
                    runner.execute(unit).completion().toCompletableFuture().get(2, TimeUnit.SECONDS);

            PersistenceTransactionResult.Unknown<Integer> unknown =
                    assertInstanceOf(PersistenceTransactionResult.Unknown.class, result);
            assertEquals("unknown_commit_readback_failed", unknown.failure().code());
            assertEquals(1, workCalls.get());
        }
    }

    @Test
    void authoritativeAbsenceResolvesUnknownAsNotCommitted() throws Exception {
        OperationId operationId = OperationId.create();
        AtomicInteger workCalls = new AtomicInteger();
        try (SqliteSingleWriter writer = writer(operationId);
             SqliteReadExecutor reads = new SqliteReadExecutor(connections)) {
            SqliteUnitOfWorkRunner runner = new SqliteUnitOfWorkRunner(writer, reads);
            SqliteUnitOfWork<Integer> unit = unit(
                    operationId,
                    connection -> {
                        workCalls.incrementAndGet();
                        return 31;
                    },
                    connection -> loadEvidence(connection, operationId)
            );

            PersistenceTransactionResult<Integer> result =
                    runner.execute(unit).completion().toCompletableFuture().get(2, TimeUnit.SECONDS);

            PersistenceTransactionResult.RolledBack<Integer> rolledBack =
                    assertInstanceOf(PersistenceTransactionResult.RolledBack.class, result);
            assertEquals("unknown_commit_proven_absent", rolledBack.failure().code());
            assertEquals(1, workCalls.get());
        }
    }

    @Test
    void knownRollbackDoesNotInvokeCommitReadback() throws Exception {
        OperationId operationId = OperationId.create();
        AtomicInteger readbackCalls = new AtomicInteger();
        try (SqliteSingleWriter writer = new SqliteSingleWriter(connections);
             SqliteReadExecutor reads = new SqliteReadExecutor(connections)) {
            SqliteUnitOfWorkRunner runner = new SqliteUnitOfWorkRunner(writer, reads);
            SqliteUnitOfWork<Integer> unit = unit(
                    operationId,
                    connection -> {
                        throw new IllegalStateException("known pre-commit failure");
                    },
                    connection -> {
                        readbackCalls.incrementAndGet();
                        return loadEvidence(connection, operationId);
                    }
            );

            PersistenceTransactionResult<Integer> result =
                    runner.execute(unit).completion().toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertInstanceOf(PersistenceTransactionResult.RolledBack.class, result);
            assertEquals(0, readbackCalls.get());
        }
    }

    private SqliteSingleWriter writer(OperationId faultedOperation) {
        return new SqliteSingleWriter(
                connections,
                new SqliteWriterConfiguration(8, 3, 0, 2_000),
                (checkpoint, operationId) -> {
                    if (checkpoint == PersistenceCheckpoint.COMMIT_RETURNED
                            && faultedOperation.equals(operationId)) {
                        throw new SQLException("injected commit return failure");
                    }
                },
                PersistenceKernelMetrics.NO_OP
        );
    }

    private SqliteUnitOfWork<Integer> unit(
            OperationId operationId,
            com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteTransactionWork<Integer> work,
            com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteReadWork<Integer> readback
    ) {
        return new SqliteUnitOfWork<>(
                new SqliteTransactionCommand<>(
                        operationId,
                        KIND,
                        TransactionReplayPolicy.SAFE_DATABASE_ONLY,
                        work
                ),
                READBACK,
                readback
        );
    }

    private void insertEvidence(Connection connection, OperationId operationId, int value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO operation_evidence(operation_id, result_value) VALUES (?, ?)"
        )) {
            statement.setString(1, operationId.toString());
            statement.setInt(2, value);
            statement.executeUpdate();
        }
    }

    private PersistenceReadResult<Integer> loadEvidence(Connection connection,
                                                        OperationId operationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT result_value FROM operation_evidence WHERE operation_id = ?"
        )) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? PersistenceReadResult.found(resultSet.getInt(1), 0)
                        : PersistenceReadResult.absent();
            }
        }
    }
}
