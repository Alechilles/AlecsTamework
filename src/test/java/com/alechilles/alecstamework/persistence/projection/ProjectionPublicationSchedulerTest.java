package com.alechilles.alecstamework.persistence.projection;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteProjectionOutboxStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteReadExecutor;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteReadExecutorConfiguration;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSingleWriter;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteUnitOfWorkRunner;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies merged projection publication lanes against a real SQLite outbox. */
class ProjectionPublicationSchedulerTest {
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private ProjectionCoordinator coordinator;
    private AtomicInteger projectionBatchReads;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(
                tempDir.resolve("tamework-state.sqlite")
        );
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        writer = new SqliteSingleWriter(connections);
        projectionBatchReads = new AtomicInteger();
        PersistenceKernelMetrics metrics = new PersistenceKernelMetrics() {
            @Override
            public void readCompleted(
                    PersistenceReadKind kind,
                    PersistenceReadResult<?> result
            ) {
                if ("projection_batch".equals(kind.value())) {
                    projectionBatchReads.incrementAndGet();
                }
            }
        };
        reads = new SqliteReadExecutor(
                connections,
                SqliteReadExecutorConfiguration.DEFAULT,
                metrics
        );
        coordinator = new ProjectionCoordinator(
                new com.alechilles.alecstamework.persistence.adapter.sqlite
                        .SqliteProjectionGateway(
                        reads,
                        new SqliteUnitOfWorkRunner(writer, reads)
                ),
                ProjectionRetryPolicy.DEFAULT,
                () -> -5_000
        );
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
    void mergesTargetsIntoOneOrderedLaneAndSkipsSupersededLowerPass()
            throws Exception {
        appendEvents(20);
        BlockingConsumer consumer = new BlockingConsumer(
                new ProjectionConsumerId("merged_consumer")
        );
        ProjectionPublicationScheduler scheduler =
                new ProjectionPublicationScheduler(coordinator);

        var first = scheduler.publish(
                consumer,
                ProjectionPublicationContext.LIVE_COMMIT,
                new ProjectionSequence(10),
                10
        ).toCompletableFuture();
        assertTrue(consumer.firstApplyEntered.await(10, TimeUnit.SECONDS));

        var second = scheduler.publish(
                consumer,
                ProjectionPublicationContext.LIVE_COMMIT,
                new ProjectionSequence(20),
                10
        ).toCompletableFuture();
        var lower = scheduler.publish(
                consumer,
                ProjectionPublicationContext.LIVE_COMMIT,
                new ProjectionSequence(15),
                10
        ).toCompletableFuture();

        assertEquals(1, scheduler.activeLaneCount());
        try {
            consumer.releaseFirst.countDown();
            ProjectionCatchUpResult firstResult = first.get(10, TimeUnit.SECONDS);
            ProjectionCatchUpResult secondResult = second.get(10, TimeUnit.SECONDS);
            ProjectionCatchUpResult lowerResult = lower.get(10, TimeUnit.SECONDS);

            assertEquals(ProjectionCatchUpResult.Status.CAUGHT_UP, firstResult.status());
            assertEquals(ProjectionCatchUpResult.Status.CAUGHT_UP, secondResult.status());
            assertEquals(ProjectionCatchUpResult.Status.CAUGHT_UP, lowerResult.status());
            assertTrue(firstResult.acknowledged().value() >= 10);
            assertTrue(secondResult.acknowledged().value() >= 20);
            assertTrue(lowerResult.acknowledged().value() >= 15);
            assertEquals(1, consumer.maxConcurrent.get());
            assertEquals(
                    List.of(
                            new ProjectionSequence(1), new ProjectionSequence(2),
                            new ProjectionSequence(3), new ProjectionSequence(4),
                            new ProjectionSequence(5), new ProjectionSequence(6),
                            new ProjectionSequence(7), new ProjectionSequence(8),
                            new ProjectionSequence(9), new ProjectionSequence(10),
                            new ProjectionSequence(11), new ProjectionSequence(12),
                            new ProjectionSequence(13), new ProjectionSequence(14),
                            new ProjectionSequence(15), new ProjectionSequence(16),
                            new ProjectionSequence(17), new ProjectionSequence(18),
                            new ProjectionSequence(19), new ProjectionSequence(20)
                    ),
                    consumer.appliedSequences
            );
            assertEquals(2, projectionBatchReads.get(),
                    "one pass to 10 and one merged pass to 20");
        } finally {
            consumer.releaseFirst.countDown();
        }
        assertEquals(0, scheduler.activeLaneCount());
    }

    @Test
    void failedRunningTargetCompletesCoveredWaitersAndContinuesHigherPendingTarget()
            throws Exception {
        appendEvents(20);
        FailFirstConsumer consumer = new FailFirstConsumer(
                new ProjectionConsumerId("failure_consumer")
        );
        ProjectionPublicationScheduler scheduler =
                new ProjectionPublicationScheduler(coordinator);

        var first = scheduler.publish(
                consumer,
                ProjectionPublicationContext.LIVE_COMMIT,
                new ProjectionSequence(10),
                10
        ).toCompletableFuture();
        assertTrue(consumer.firstApplyEntered.await(10, TimeUnit.SECONDS));

        var higher = scheduler.publish(
                consumer,
                ProjectionPublicationContext.LIVE_COMMIT,
                new ProjectionSequence(20),
                10
        ).toCompletableFuture();
        assertEquals(1, scheduler.activeLaneCount());

        try {
            consumer.releaseFirst.countDown();
            ProjectionCatchUpResult firstResult = first.get(10, TimeUnit.SECONDS);
            ProjectionCatchUpResult higherResult = higher.get(10, TimeUnit.SECONDS);

            assertEquals(
                    ProjectionCatchUpResult.Status.CONSUMER_FAILED,
                    firstResult.status()
            );
            assertEquals(
                    "injected running target failure",
                    firstResult.failure().getMessage()
            );
            assertEquals(
                    ProjectionCatchUpResult.Status.CAUGHT_UP,
                    higherResult.status()
            );
            assertTrue(higherResult.acknowledged().value() >= 20);
            assertEquals(
                    3,
                    projectionBatchReads.get(),
                    "the pending target continues in one follow-up publication"
            );
            assertEquals(21, consumer.appliedSequences.size());
        } finally {
            consumer.releaseFirst.countDown();
        }
        assertEquals(0, scheduler.activeLaneCount());
    }

    @Test
    void liveAndRecoveryContextsUseIndependentSerialLanes() throws Exception {
        appendEvents(1);
        ContextBlockingConsumer consumer = new ContextBlockingConsumer(
                new ProjectionConsumerId("context_consumer")
        );
        ProjectionPublicationScheduler scheduler =
                new ProjectionPublicationScheduler(coordinator);

        var live = scheduler.publish(
                consumer,
                ProjectionPublicationContext.LIVE_COMMIT,
                new ProjectionSequence(1),
                10
        ).toCompletableFuture();
        assertTrue(consumer.entered(
                ProjectionPublicationContext.LIVE_COMMIT
        ).await(10, TimeUnit.SECONDS));

        var recovery = scheduler.publish(
                consumer,
                ProjectionPublicationContext.RECOVERY_CONVERGENCE,
                new ProjectionSequence(1),
                10
        ).toCompletableFuture();
        assertTrue(consumer.entered(
                ProjectionPublicationContext.RECOVERY_CONVERGENCE
        ).await(10, TimeUnit.SECONDS));
        assertEquals(2, scheduler.activeLaneCount());

        consumer.release(ProjectionPublicationContext.LIVE_COMMIT);
        consumer.release(ProjectionPublicationContext.RECOVERY_CONVERGENCE);

        assertEquals(
                ProjectionCatchUpResult.Status.CAUGHT_UP,
                live.get(10, TimeUnit.SECONDS).status()
        );
        assertEquals(
                ProjectionCatchUpResult.Status.CAUGHT_UP,
                recovery.get(10, TimeUnit.SECONDS).status()
        );
        assertEquals(
                Set.of(
                        ProjectionPublicationContext.LIVE_COMMIT,
                        ProjectionPublicationContext.RECOVERY_CONVERGENCE
                ),
                Set.copyOf(consumer.contexts)
        );
        assertEquals(2, consumer.maxConcurrent.get());
        assertEquals(0, scheduler.activeLaneCount());
    }

    private void appendEvents(int count) throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            new SqliteOperationStore(connection).prepare(new PreparedOperation(
                    OPERATION,
                    new IdempotencyKey("projection-scheduler-test"),
                    new OperationKind("projection_test"),
                    1,
                    "{}",
                    "test",
                    null,
                    List.of(),
                    -10_000
            ));
            SqliteProjectionOutboxStore store =
                    new SqliteProjectionOutboxStore(connection);
            for (int revision = 1; revision <= count; revision++) {
                store.append(new ProjectionEventDraft(
                        OPERATION,
                        new ProjectionEventType("lifecycle_changed"),
                        "profile-a",
                        revision,
                        1,
                        "{\"revision\":" + revision + "}",
                        -10_000 + revision
                ));
            }
            connection.commit();
        }
    }

    private static final class BlockingConsumer implements ProjectionConsumer {
        private final ProjectionConsumerId consumerId;
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();
        private final CountDownLatch firstApplyEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final List<ProjectionSequence> appliedSequences =
                java.util.Collections.synchronizedList(new ArrayList<>());

        private BlockingConsumer(ProjectionConsumerId consumerId) {
            this.consumerId = consumerId;
        }

        @Override
        public ProjectionConsumerId consumerId() {
            return consumerId;
        }

        @Override
        public ProjectionApplyOutcome apply(ProjectionEvent event) throws Exception {
            int concurrent = active.incrementAndGet();
            maxConcurrent.accumulateAndGet(concurrent, Math::max);
            try {
                if (first.compareAndSet(true, false)) {
                    firstApplyEntered.countDown();
                    if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("first apply was not released");
                    }
                }
                appliedSequences.add(event.sequence());
                return ProjectionApplyOutcome.APPLIED;
            } finally {
                active.decrementAndGet();
            }
        }
    }

    private static final class FailFirstConsumer implements ProjectionConsumer {
        private final ProjectionConsumerId consumerId;
        private final AtomicBoolean failFirst = new AtomicBoolean(true);
        private final CountDownLatch firstApplyEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final List<ProjectionSequence> appliedSequences =
                java.util.Collections.synchronizedList(new ArrayList<>());

        private FailFirstConsumer(ProjectionConsumerId consumerId) {
            this.consumerId = consumerId;
        }

        @Override
        public ProjectionConsumerId consumerId() {
            return consumerId;
        }

        @Override
        public ProjectionApplyOutcome apply(ProjectionEvent event)
                throws Exception {
            appliedSequences.add(event.sequence());
            if (failFirst.compareAndSet(true, false)) {
                firstApplyEntered.countDown();
                if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("first apply was not released");
                }
                throw new IllegalStateException("injected running target failure");
            }
            return ProjectionApplyOutcome.APPLIED;
        }
    }

    private static final class ContextBlockingConsumer
            implements ProjectionConsumer {
        private final ProjectionConsumerId consumerId;
        private final java.util.Map<ProjectionPublicationContext, CountDownLatch>
                entered = new java.util.EnumMap<>(ProjectionPublicationContext.class);
        private final java.util.Map<ProjectionPublicationContext, CountDownLatch>
                releases = new java.util.EnumMap<>(ProjectionPublicationContext.class);
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();
        private final List<ProjectionPublicationContext> contexts =
                java.util.Collections.synchronizedList(new ArrayList<>());

        private ContextBlockingConsumer(ProjectionConsumerId consumerId) {
            this.consumerId = consumerId;
            for (ProjectionPublicationContext context :
                    ProjectionPublicationContext.values()) {
                entered.put(context, new CountDownLatch(1));
                releases.put(context, new CountDownLatch(1));
            }
        }

        private CountDownLatch entered(ProjectionPublicationContext context) {
            return entered.get(context);
        }

        private void release(ProjectionPublicationContext context) {
            releases.get(context).countDown();
        }

        @Override
        public ProjectionConsumerId consumerId() {
            return consumerId;
        }

        @Override
        public ProjectionApplyOutcome apply(ProjectionEvent event) {
            throw new AssertionError("The context-aware overload is required");
        }

        @Override
        public ProjectionApplyOutcome apply(
                ProjectionEvent event,
                ProjectionPublicationContext context
        ) throws Exception {
            int concurrent = active.incrementAndGet();
            maxConcurrent.accumulateAndGet(concurrent, Math::max);
            try {
                entered.get(context).countDown();
                if (!releases.get(context).await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "context apply was not released: " + context
                    );
                }
                contexts.add(context);
                return ProjectionApplyOutcome.APPLIED;
            } finally {
                active.decrementAndGet();
            }
        }
    }
}
