package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.persistence.incidents.PersistenceDomain;
import com.alechilles.alecstamework.persistence.operation.PersistenceOperationMetadata;
import com.alechilles.alecstamework.persistence.operation.PersistenceWriteFailureHandler;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceWriteQueueIsolationTest {
    @TempDir
    Path tempDir;

    @Test
    void confirmedRollbackIsolatesFailingTaskAndCommitsUnrelatedTasksInOrder() throws Exception {
        Harness harness = new Harness("isolation.sqlite");
        PersistenceWriteTask<Void> first = harness.insert("first", "group-1", 1);
        PersistenceWriteTask<Void> failing = harness.failure("failing", "group-2");
        PersistenceWriteTask<Void> third = harness.insert("third", "group-3", 3);

        harness.executor.execute(List.of(first, failing, third), false);

        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, harness.outcome(first).status());
        assertEquals(PersistenceWriteQueue.WriteStatus.FAILED, harness.outcome(failing).status());
        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, harness.outcome(third).status());
        assertEquals(List.of(1, 3), harness.values());
        assertEquals(List.of("failing"), harness.rolledBackNames);
        assertTrue(harness.health.isHealthy());
    }

    @Test
    void declaredAtomicGroupIsNeverSplitDuringIsolation() throws Exception {
        Harness harness = new Harness("atomic.sqlite");
        PersistenceWriteTask<Void> first = harness.insert("first", "group-1", 1);
        PersistenceWriteTask<Void> groupedInsert = harness.insert("grouped-insert", "group-2", 2);
        PersistenceWriteTask<Void> groupedFailure = harness.failure("grouped-failure", "group-2");
        PersistenceWriteTask<Void> fourth = harness.insert("fourth", "group-3", 4);

        harness.executor.execute(List.of(first, groupedInsert, groupedFailure, fourth), false);

        assertEquals(PersistenceWriteQueue.WriteStatus.FAILED, harness.outcome(groupedInsert).status());
        assertEquals(PersistenceWriteQueue.WriteStatus.FAILED, harness.outcome(groupedFailure).status());
        assertEquals(List.of(1, 4), harness.values());
        assertEquals(List.of("grouped-insert", "grouped-failure"), harness.rolledBackNames);
    }

    @Test
    void publicationFailureCannotRetroactivelyRollBackCommittedTransaction() throws Exception {
        Harness harness = new Harness("publication.sqlite");
        PersistenceWriteTask<Void> task = new PersistenceWriteTask<>(
                harness.metadata("committed", "group-1"),
                connection -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("INSERT INTO writes(value) VALUES (7)");
                    }
                    return null;
                },
                ignored -> { throw new IllegalStateException("publication failed"); });

        harness.executor.execute(List.of(task), false);

        assertEquals(PersistenceWriteQueue.WriteStatus.COMMITTED, harness.outcome(task).status());
        assertEquals(List.of(7), harness.values());
        assertEquals(List.of("committed"), harness.publicationFailureNames);
        assertTrue(harness.health.isHealthy());
    }

    private final class Harness {
        private final SqliteConnectionManager connections;
        private final PersistenceHealthService health = new PersistenceHealthService();
        private final List<String> rolledBackNames = new ArrayList<>();
        private final List<String> publicationFailureNames = new ArrayList<>();
        private final PersistenceWriteBatchExecutor executor;

        private Harness(String filename) throws Exception {
            connections = new SqliteConnectionManager(tempDir.resolve(filename));
            try (Connection connection = connections.openConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE writes(value INTEGER NOT NULL)");
            }
            AtomicReference<PersistenceWriteFailureHandler> handler = new AtomicReference<>(listener());
            executor = new PersistenceWriteBatchExecutor(
                    connections, health, new PersistenceWriteQueueMetrics(), handler, null);
        }

        private PersistenceWriteTask<Void> insert(String name, String group, int value) {
            return new PersistenceWriteTask<>(metadata(name, group), connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO writes(value) VALUES (" + value + ")");
                }
                return null;
            }, null);
        }

        private PersistenceWriteTask<Void> failure(String name, String group) {
            return new PersistenceWriteTask<>(metadata(name, group), connection -> {
                throw new IllegalStateException("deterministic domain conflict");
            }, null);
        }

        private PersistenceOperationMetadata metadata(String name, String group) {
            return PersistenceOperationMetadata.builder(name, PersistenceDomain.OWNER_MUTATION)
                    .atomicGroupId(group)
                    .build();
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
                public void rolledBack(PersistenceOperationMetadata metadata, Throwable failure) {
                    rolledBackNames.add(metadata.taskName());
                }

                @Override
                public void commitOutcomeUnknown(List<PersistenceOperationMetadata> metadata,
                                                 Throwable failure) { }

                @Override
                public void publicationFailed(PersistenceOperationMetadata metadata, Throwable failure) {
                    publicationFailureNames.add(metadata.taskName());
                }
            };
        }
    }
}
