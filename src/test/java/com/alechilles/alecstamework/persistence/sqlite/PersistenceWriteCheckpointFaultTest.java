package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.operation.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.operation.PersistenceOperationMetadata;
import com.alechilles.alecstamework.persistence.operation.PersistenceWriteFailureHandler;
import com.alechilles.alecstamework.persistence.testing.DeterministicPersistenceFaultInjector;
import com.alechilles.alecstamework.persistence.testing.DeterministicPersistenceFaultInjector.FaultMode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceWriteCheckpointFaultTest {
    @TempDir
    Path tempDir;

    @Test
    void injectedBusyFailuresRetryDeterministicallyThenCommit() throws Exception {
        DeterministicPersistenceFaultInjector faults = new DeterministicPersistenceFaultInjector()
                .arm("writer-busy-2", PersistenceCheckpoint.BEFORE_FIRST_SQL_STATEMENT,
                        FaultMode.SQLITE_BUSY_LOCKED, 2);
        Harness harness = new Harness("busy.sqlite", faults);
        PersistenceWriteTask<Void> task = harness.insert("busy-retry", 1, null);

        harness.executor.execute(List.of(task), false);

        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, harness.outcome(task).status());
        assertEquals(List.of(1), harness.values());
        assertEquals(2L, harness.metrics.snapshot(0).retryAttempts());
        assertTrue(harness.health.isHealthy());
    }

    @Test
    void exceptionImmediatelyAfterCommitCannotTurnCommittedStateIntoRollback() throws Exception {
        DeterministicPersistenceFaultInjector faults = new DeterministicPersistenceFaultInjector()
                .arm("known-present", PersistenceCheckpoint.AFTER_COMMIT_RETURN,
                        FaultMode.EXCEPTION_COMMIT_KNOWN_PRESENT, 1);
        Harness harness = new Harness("known-present.sqlite", faults);
        AtomicInteger publications = new AtomicInteger();
        PersistenceWriteTask<Void> task = harness.insert("known-present", 2,
                publications::incrementAndGet);

        harness.executor.execute(List.of(task), false);

        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, harness.outcome(task).status());
        assertEquals(List.of(2), harness.values());
        assertEquals(1, publications.get());
        assertTrue(harness.health.isHealthy());
    }

    @Test
    void runtimePublicationFaultLeavesCommittedRowAndReportsOnlyPublication() throws Exception {
        DeterministicPersistenceFaultInjector faults = new DeterministicPersistenceFaultInjector()
                .arm("publish-failure", PersistenceCheckpoint.BEFORE_RUNTIME_INDEX_PUBLICATION,
                        FaultMode.RUNTIME_PUBLICATION_EXCEPTION, 1);
        Harness harness = new Harness("publication.sqlite", faults);
        PersistenceWriteTask<Void> task = harness.insert("publish-failure", 3, () -> { });

        harness.executor.execute(List.of(task), false);

        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, harness.outcome(task).status());
        assertEquals(List.of(3), harness.values());
        assertEquals(List.of("publish-failure"), harness.publicationFailures);
        assertTrue(harness.health.isHealthy());
    }

    private final class Harness {
        private final SqliteConnectionManager connections;
        private final PersistenceHealthService health = new PersistenceHealthService();
        private final PersistenceWriteQueueMetrics metrics = new PersistenceWriteQueueMetrics();
        private final List<String> publicationFailures = new ArrayList<>();
        private final PersistenceWriteBatchExecutor executor;

        private Harness(String filename, DeterministicPersistenceFaultInjector faults) throws Exception {
            connections = new SqliteConnectionManager(tempDir.resolve(filename));
            try (Connection connection = connections.openConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE writes(value INTEGER NOT NULL)");
            }
            AtomicReference<PersistenceWriteFailureHandler> handler =
                    new AtomicReference<>(listener());
            executor = new PersistenceWriteBatchExecutor(
                    connections, health, metrics, handler, null, faults);
        }

        private PersistenceWriteTask<Void> insert(String name, int value, Runnable publication) {
            PersistenceOperationMetadata metadata = PersistenceOperationMetadata.builder(
                            name, PersistenceDomain.OWNER_MUTATION)
                    .atomicGroupId(name)
                    .build();
            return new PersistenceWriteTask<>(metadata, connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO writes(value) VALUES (" + value + ")");
                }
                return null;
            }, ignored -> {
                if (publication != null) publication.run();
            });
        }

        private PersistenceWriteQueue.WriteOutcome<Void> outcome(PersistenceWriteTask<Void> task)
                throws Exception {
            return task.completion().get(1, TimeUnit.SECONDS);
        }

        private List<Integer> values() throws Exception {
            try (Connection connection = connections.openConnection();
                 Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT value FROM writes ORDER BY rowid")) {
                List<Integer> values = new ArrayList<>();
                while (result.next()) values.add(result.getInt(1));
                return List.copyOf(values);
            }
        }

        private PersistenceWriteFailureHandler listener() {
            return new PersistenceWriteFailureHandler() {
                @Override
                public void rolledBack(PersistenceOperationMetadata metadata, Throwable failure) { }

                @Override
                public void commitOutcomeUnknown(List<PersistenceOperationMetadata> metadata,
                                                 Throwable failure) { }

                @Override
                public void publicationFailed(PersistenceOperationMetadata metadata,
                                              Throwable failure) {
                    publicationFailures.add(metadata.taskName());
                }
            };
        }
    }
}
