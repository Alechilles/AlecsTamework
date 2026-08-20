package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteProjectionGateway;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteProjectionOutboxStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteReadExecutor;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV2Manager;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSingleWriter;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteUnitOfWorkRunner;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionCatchUpResult;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import com.alechilles.alecstamework.persistence.projection.ProjectionSubscription;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loose cross-machine numeric gates plus exact bounded-resource assertions.
 *
 * <p>Production-like copied-world and tick-delta budgets remain live rehearsal
 * gates; this test catches order-of-magnitude regressions in the same JVM as the
 * normal suite.</p>
 */
class ReplacementPersistencePerformanceGateTest {
    private static final long STARTUP_NODE_BUDGET_NS =
            Duration.ofSeconds(5).toNanos();
    private static final long OPERATION_P99_BUDGET_NS =
            Duration.ofSeconds(2).toNanos();
    private static final int OPERATIONS = 64;

    @TempDir
    Path tempDir;

    @Test
    void startupTransactionsQueuesAndShutdownStayWithinReleaseBudgets() {
        PublicPersistenceRuntime runtime = runtime();

        assertTrue(runtime.start().toCompletableFuture().join().complete());
        for (int sequence = 0; sequence < OPERATIONS; sequence++) {
            var submission = runtime.operations().mutateProfile(
                    OperationId.create(),
                    new IdempotencyKey("performance-" + sequence),
                    create(sequence)
            );
            assertTrue(submission.accepted());
            assertEquals(
                    com.alechilles.alecstamework.persistence.operation
                            .OperationWorkflowResult.Status.PUBLISHED,
                    submission.completion().toCompletableFuture()
                            .join().status()
            );
        }

        PublicPersistencePerformanceSnapshot active =
                runtime.performance();
        assertStartupBudget(
                active, PersistenceStartupNode.OPEN_TARGET
        );
        assertStartupBudget(
                active, PersistenceStartupNode.LOAD_CANONICAL
        );
        assertStartupBudget(
                active, PersistenceStartupNode.BUILD_PROJECTIONS
        );
        assertTrue(active.writer().execution().count() >= OPERATIONS);
        assertTrue(
                active.writer().execution().p99Nanos()
                        <= OPERATION_P99_BUDGET_NS
        );
        assertTrue(
                active.writer().queueWait().p99Nanos()
                        <= OPERATION_P99_BUDGET_NS
        );
        assertTrue(active.writer().maximumDepth() <= 1_024);
        assertTrue(active.reads().maximumDepth() <= 256);

        assertTrue(runtime.shutdown(Duration.ofSeconds(5)).terminal());
        PublicPersistencePerformanceSnapshot closed =
                runtime.performance();
        assertEquals(1, closed.shutdownDrain().count());
        assertTrue(
                closed.shutdownDrain().p99Nanos()
                        <= Duration.ofSeconds(5).toNanos()
        );
        assertTrue(
                closed.lastCheckpointedFrames()
                        <= closed.lastCheckpointLogFrames()
        );
    }

    @Test
    void routedProjectionMetricsCountBypassedWorkInsteadOfPayloads() throws Exception {
        long startedAtNanos = System.nanoTime();
        SqliteConnectionFactory connections = new SqliteConnectionFactory(
                tempDir.resolve("routed-metrics.sqlite")
        );
        new SqliteSchemaV2Manager(connections, () -> -10_000).initialize();
        PublicPersistenceControlPlane metrics =
                new PublicPersistenceControlPlane(
                        PublicPersistenceFeatureRegistry.create()
                );
        SqliteSingleWriter writer = new SqliteSingleWriter(
                connections,
                com.alechilles.alecstamework.persistence.adapter.sqlite
                        .SqliteWriterConfiguration.DEFAULT,
                com.alechilles.alecstamework.persistence.kernel
                        .PersistenceCheckpointHook.NO_OP,
                metrics
        );
        SqliteReadExecutor reads = new SqliteReadExecutor(
                connections,
                com.alechilles.alecstamework.persistence.adapter.sqlite
                        .SqliteReadExecutorConfiguration.DEFAULT,
                metrics
        );
        try {
            ProjectionEvent target;
            try (Connection connection = connections.openWriterConnection()) {
                connection.setAutoCommit(false);
                OperationId operationId = OperationId.parse(
                        "50000000-0000-0000-0000-000000000001"
                );
                new SqliteOperationStore(connection).prepare(
                        new PreparedOperation(
                                operationId,
                                new IdempotencyKey("routed-metrics"),
                                new OperationKind("routed_metrics"),
                                1,
                                "{}",
                                "test",
                                null,
                                List.of(),
                                -10_000
                        )
                );
                SqliteProjectionOutboxStore outbox =
                        new SqliteProjectionOutboxStore(connection);
                for (int revision = 1; revision <= 10_000; revision++) {
                    outbox.append(new ProjectionEventDraft(
                            operationId,
                            new ProjectionEventType(
                                    "profile_extension_mutated"
                            ),
                            "profile-routed",
                            revision,
                            1,
                            "{\"revision\":" + revision + "}",
                            -10_000 + revision
                    ));
                }
                target = outbox.append(new ProjectionEventDraft(
                        operationId,
                        new ProjectionEventType("lifecycle_changed"),
                        "profile-routed",
                        10_001,
                        1,
                        "{\"revision\":10001}",
                        1
                )).value();
                connection.commit();
            }
            ProjectionCoordinator coordinator = new ProjectionCoordinator(
                    new SqliteProjectionGateway(
                            reads,
                            new SqliteUnitOfWorkRunner(writer, reads),
                            metrics
                    ),
                    ProjectionRetryPolicy.DEFAULT,
                    () -> -5_000
            );
            CountingRoutedConsumer consumer = new CountingRoutedConsumer();
            var result = coordinator.afterCommit(
                    consumer, target.sequence(), 10_000
            ).toCompletableFuture().join();

            assertEquals(ProjectionCatchUpResult.Status.CAUGHT_UP, result.status());
            assertEquals(target.sequence(), result.acknowledged());
            assertEquals(1, consumer.applyCalls.get());
            assertEquals(1, result.deliveredCount());
            var throughput = metrics.snapshot();
            assertTrue(
                    throughput.projectionSequencePositionsBypassed()
                            >= 10_000
            );
            assertTrue(throughput.projectionBatchAcknowledgements() <= 2);
            assertTrue(throughput.writerMaximumDepth() < 64);
            assertEquals(0, throughput.readSaturationFailures());
        } finally {
            writer.shutdown(Duration.ofSeconds(5));
            reads.shutdown(Duration.ofSeconds(5));
        }
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        assertTrue(
                elapsedNanos <= Duration.ofSeconds(10).toNanos(),
                () -> "Routed projection gate exceeded 10 seconds: "
                        + Duration.ofNanos(elapsedNanos)
        );
    }

    private void assertStartupBudget(
            PublicPersistencePerformanceSnapshot snapshot,
            PersistenceStartupNode node
    ) {
        var timing = snapshot.startupNodes().get(node);
        assertEquals(1, timing.count());
        assertTrue(
                timing.p99Nanos() <= STARTUP_NODE_BUDGET_NS,
                () -> node + " exceeded startup budget: "
                        + timing.p99Nanos()
        );
    }

    private PublicPersistenceRuntime runtime() {
        return new PublicPersistenceRuntime(
                new PublicPersistenceRuntimeConfiguration(
                        tempDir,
                        "performance-gate",
                        System::currentTimeMillis,
                        (claim, operation) -> LiveOperationResult
                                .confirmed("refund_confirmed")
                                .completed(),
                        event -> {
                        },
                        boundaries(),
                        PublicPersistenceWorldReconciliation
                                .alreadyComplete(),
                        Duration.ofSeconds(5)
                )
        );
    }

    private PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (request, operation) -> confirmed("capture"),
                (request, operation) -> confirmed("capture_release"),
                (request, operation) -> confirmed("restoration"),
                (request, operation) -> confirmed("coop_capture"),
                (request, operation) -> confirmed("coop_release")
        );
    }

    private java.util.concurrent.CompletionStage<LiveOperationResult>
    confirmed(String receipt) {
        return LiveOperationResult.confirmed(receipt).completed();
    }

    private CompanionProfileMutation.Create create(int sequence) {
        ProfileId profileId = ProfileId.parse(
                UUID.nameUUIDFromBytes(
                        ("profile-" + sequence).getBytes(
                                StandardCharsets.UTF_8
                        )
                ).toString()
        );
        String metadata = "{\"performance\":" + sequence + "}";
        long now = -1_000L - sequence;
        return new CompanionProfileMutation.Create(
                new CompanionIdentity(
                        profileId,
                        "Companion " + sequence,
                        "role",
                        metadata,
                        Sha256Hash.ofUtf8(metadata),
                        "world",
                        now,
                        now,
                        now,
                        0
                ),
                new CompanionLifecycle(
                        profileId,
                        OwnerId.parse(
                                "10000000-0000-0000-0000-000000000001"
                        ),
                        LifecycleState.UNLOADED,
                        LifecycleLocation.none(),
                        LifecycleRevision.INITIAL,
                        null,
                        now,
                        ReconciliationGeneration.INITIAL,
                        null
                ),
                java.util.List.of(),
                now
        );
    }

    private static final class CountingRoutedConsumer
            implements ProjectionConsumer {
        private final AtomicInteger applyCalls = new AtomicInteger();

        @Override
        public ProjectionConsumerId consumerId() {
            return new ProjectionConsumerId("routed_metrics_consumer");
        }

        @Override
        public ProjectionSubscription subscription() {
            return ProjectionSubscription.events(Set.of(
                    new ProjectionEventType("lifecycle_changed")
            ));
        }

        @Override
        public ProjectionApplyOutcome apply(ProjectionEvent event) {
            applyCalls.incrementAndGet();
            return ProjectionApplyOutcome.APPLIED;
        }
    }
}
