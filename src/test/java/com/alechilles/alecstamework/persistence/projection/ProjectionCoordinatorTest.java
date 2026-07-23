package com.alechilles.alecstamework.persistence.projection;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteProjectionGateway;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteProjectionOutboxStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteReadExecutor;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSingleWriter;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteUnitOfWorkRunner;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteWriterConfiguration;
import com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** End-to-end after-commit, replay, retry, and rebuild tests for projection delivery. */
class ProjectionCoordinatorTest {
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private ProjectionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        coordinator = coordinator(writer, reads);
    }

    @AfterEach
    void tearDown() {
        if (writer != null) {
            writer.shutdown(Duration.ofSeconds(5));
        }
        if (reads != null) {
            reads.shutdown(Duration.ofSeconds(5));
        }
    }

    @Test
    void startupCatchUpAndDuplicateReplayProduceTheSameRevisionedProjection()
            throws Exception {
        List<ProjectionEvent> events = appendEvents();
        RevisionConsumer consumer = new RevisionConsumer("test_index", 0);

        ProjectionCatchUpResult initial =
                coordinator.startupCatchUp(consumer, 1).toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
        assertEquals(ProjectionCatchUpResult.Status.CAUGHT_UP, initial.status());
        assertEquals(events.getLast().sequence(), initial.acknowledged());
        assertEquals(2L, consumer.revisions.get("profile-a"));

        rewindCheckpoint(consumer.consumerId());
        ProjectionCatchUpResult replay =
                coordinator.startupCatchUp(consumer, 1).toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
        assertEquals(ProjectionCatchUpResult.Status.CAUGHT_UP, replay.status());
        assertEquals(4, consumer.applyCalls.get());
        assertEquals(2L, consumer.revisions.get("profile-a"));
    }

    @Test
    void afterCommitStopsAtItsCommittedSequenceThenStartupCatchesTheRemainder()
            throws Exception {
        List<ProjectionEvent> events = appendEvents();
        RevisionConsumer consumer = new RevisionConsumer("after_commit_index", 0);

        ProjectionCatchUpResult first =
                coordinator.afterCommit(consumer, events.getFirst().sequence(), 10)
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(events.getFirst().sequence(), first.acknowledged());
        assertEquals(1L, consumer.revisions.get("profile-a"));

        ProjectionCatchUpResult startup =
                coordinator.startupCatchUp(consumer, 10).toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);
        assertEquals(events.getLast().sequence(), startup.acknowledged());
        assertEquals(2L, consumer.revisions.get("profile-a"));
    }

    @Test
    void consumerFailureLeavesTheEventPendingAndUsesBoundedBackoff() throws Exception {
        appendEvents();
        RevisionConsumer consumer = new RevisionConsumer("retry_index", 2);

        ProjectionCatchUpResult first = coordinator.startupCatchUp(consumer, 10)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        ProjectionCatchUpResult second = coordinator.startupCatchUp(consumer, 10)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        ProjectionCatchUpResult recovered = coordinator.startupCatchUp(consumer, 10)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(ProjectionCatchUpResult.Status.CONSUMER_FAILED, first.status());
        assertEquals(100, first.retryAfterMs());
        assertEquals(200, second.retryAfterMs());
        assertEquals(ProjectionCatchUpResult.Status.CAUGHT_UP, recovered.status());
        assertEquals(2L, consumer.revisions.get("profile-a"));
    }

    @Test
    void unknownCheckpointCommitUsesExactReadbackInsteadOfRedelivery() throws Exception {
        List<ProjectionEvent> events = appendOneEvent();
        writer.shutdown(Duration.ofSeconds(5));
        writer = new SqliteSingleWriter(
                connections,
                SqliteWriterConfiguration.DEFAULT,
                (checkpoint, ignored) -> {
                    if (checkpoint == PersistenceCheckpoint.COMMIT_RETURNED) {
                        throw new IllegalStateException("injected_after_commit");
                    }
                },
                PersistenceKernelMetrics.NO_OP
        );
        coordinator = coordinator(writer, reads);
        RevisionConsumer consumer = new RevisionConsumer("unknown_commit_index", 0);

        ProjectionCatchUpResult result =
                coordinator.afterCommit(consumer, events.getFirst().sequence(), 10)
                        .toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(ProjectionCatchUpResult.Status.CAUGHT_UP, result.status());
        assertEquals(1, consumer.applyCalls.get());
        assertEquals(events.getFirst().sequence(), result.acknowledged());
    }

    @Test
    void canonicalRebuildComparisonIsExplicit() {
        assertEquals(
                ProjectionRebuildResult.Status.EQUIVALENT,
                coordinator.verifyRebuild(probe(Map.of("profile", 2L), Map.of("profile", 2L)))
                        .status()
        );
        assertEquals(
                ProjectionRebuildResult.Status.MISMATCH,
                coordinator.verifyRebuild(probe(Map.of("profile", 2L), Map.of("profile", 1L)))
                        .status()
        );
        ProjectionRebuildResult failed = coordinator.verifyRebuild(
                new ProjectionRebuildProbe<Map<String, Long>>() {
            @Override
            public Map<String, Long> rebuildCanonical() {
                throw new IllegalStateException("read failed");
            }

            @Override
            public Map<String, Long> readProjection() {
                return Map.of();
            }

            @Override
            public boolean equivalent(Map<String, Long> canonical,
                                      Map<String, Long> projected) {
                return canonical.equals(projected);
            }
                });
        assertEquals(ProjectionRebuildResult.Status.FAILED, failed.status());
        assertNotNull(failed.failure());
    }

    private ProjectionCoordinator coordinator(SqliteSingleWriter writer,
                                              SqliteReadExecutor reads) {
        return new ProjectionCoordinator(
                new SqliteProjectionGateway(
                        reads,
                        new SqliteUnitOfWorkRunner(writer, reads)
                ),
                ProjectionRetryPolicy.DEFAULT,
                () -> -5_000
        );
    }

    private List<ProjectionEvent> appendEvents() throws Exception {
        try (Connection connection = transaction()) {
            SqliteProjectionOutboxStore store = createOperationAndStore(connection);
            ProjectionEvent first = store.append(draft(1)).value();
            ProjectionEvent second = store.append(draft(2)).value();
            connection.commit();
            return List.of(first, second);
        }
    }

    private List<ProjectionEvent> appendOneEvent() throws Exception {
        try (Connection connection = transaction()) {
            SqliteProjectionOutboxStore store = createOperationAndStore(connection);
            ProjectionEvent event = store.append(draft(1)).value();
            connection.commit();
            return List.of(event);
        }
    }

    private SqliteProjectionOutboxStore createOperationAndStore(Connection connection) {
        new SqliteOperationStore(connection).prepare(new PreparedOperation(
                OPERATION, new IdempotencyKey("projection-coordinator-test"),
                new OperationKind("projection_test"), 1, "{}", "test",
                null, List.of(), -10_000
        ));
        return new SqliteProjectionOutboxStore(connection);
    }

    private ProjectionEventDraft draft(long revision) {
        return new ProjectionEventDraft(
                OPERATION, new ProjectionEventType("lifecycle_changed"),
                "profile-a", revision, 1, "{\"revision\":" + revision + "}",
                -10_000 + revision
        );
    }

    private void rewindCheckpoint(ProjectionConsumerId consumerId) throws Exception {
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE projection_checkpoint
                     SET acknowledged_sequence = 0
                     WHERE consumer_id = ?
                     """)) {
            statement.setString(1, consumerId.toString());
            statement.executeUpdate();
        }
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private ProjectionRebuildProbe<Map<String, Long>> probe(
            Map<String, Long> canonical,
            Map<String, Long> projected
    ) {
        return new ProjectionRebuildProbe<>() {
            @Override
            public Map<String, Long> rebuildCanonical() {
                return canonical;
            }

            @Override
            public Map<String, Long> readProjection() {
                return projected;
            }

            @Override
            public boolean equivalent(Map<String, Long> left, Map<String, Long> right) {
                return left.equals(right);
            }
        };
    }

    private static final class RevisionConsumer implements ProjectionConsumer {
        private final ProjectionConsumerId consumerId;
        private final AtomicInteger failuresRemaining;
        private final AtomicInteger applyCalls = new AtomicInteger();
        private final Map<String, Long> revisions = new HashMap<>();

        private RevisionConsumer(String consumerId, int failuresRemaining) {
            this.consumerId = new ProjectionConsumerId(consumerId);
            this.failuresRemaining = new AtomicInteger(failuresRemaining);
        }

        @Override
        public ProjectionConsumerId consumerId() {
            return consumerId;
        }

        @Override
        public ProjectionApplyOutcome apply(ProjectionEvent event) {
            applyCalls.incrementAndGet();
            if (failuresRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("injected projection failure");
            }
            long current = revisions.getOrDefault(event.aggregateId(), -1L);
            if (current >= event.aggregateRevision()) {
                return ProjectionApplyOutcome.ALREADY_APPLIED;
            }
            revisions.put(event.aggregateId(), event.aggregateRevision());
            return ProjectionApplyOutcome.APPLIED;
        }
    }
}
