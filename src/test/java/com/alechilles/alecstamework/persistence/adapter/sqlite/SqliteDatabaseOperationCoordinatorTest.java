package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.projection.ProjectionApplyOutcome;
import com.alechilles.alecstamework.persistence.projection.ContextualProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.alechilles.alecstamework.persistence.projection.ProjectionPublicationContext;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end tests for the one database-only operation workflow. */
class SqliteDatabaseOperationCoordinatorTest {
    private static final OperationKind KIND = new OperationKind("profile_create");
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000001");
    private static final OperationId CONTEXT_RECOVERY_OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000002");
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final ProfileId CONTEXT_RECOVERY_PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000002");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private SqliteDatabaseOperationCoordinator coordinator;
    private TestDefinition definition;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        definition = new TestDefinition();
        OperationDefinitionRegistry definitions =
                new OperationDefinitionRegistry(List.of(definition));
        SqliteUnitOfWorkRunner transactions = new SqliteUnitOfWorkRunner(writer, reads);
        coordinator = new SqliteDatabaseOperationCoordinator(
                new SqliteOperationEngine(definitions, transactions),
                new SqliteOperationEvidenceReader(reads),
                new ProjectionCoordinator(
                        new SqliteProjectionGateway(reads, transactions),
                        ProjectionRetryPolicy.DEFAULT,
                        () -> -5_000
                ),
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
    void preparedOperationCommitsProjectsAndPublishesThroughOneWorkflow()
            throws Exception {
        RevisionConsumer consumer = new RevisionConsumer("profile_view", 0);

        OperationWorkflowResult result = execute(new AtomicInteger(), consumer);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, result.status());
        assertEquals(OperationPhase.PUBLISHED, result.operation().phase());
        assertEquals(1, result.events().size());
        assertEquals(1L, consumer.revisions.get(PROFILE.toString()));
        assertEquals(1, consumer.applyCalls.get());
        assertEquals(OperationPhase.PUBLISHED, storedOperation().phase());
    }

    @Test
    void retryAfterProjectionFailureUsesDurableEvidenceWithoutRerunningMutation()
            throws Exception {
        AtomicInteger durableExecutions = new AtomicInteger();
        RevisionConsumer failing = new RevisionConsumer("profile_view", 1);

        OperationWorkflowResult first = execute(durableExecutions, failing);

        assertEquals(OperationWorkflowResult.Status.PUBLICATION_PENDING, first.status());
        assertEquals(OperationPhase.DURABLE, first.operation().phase());
        assertEquals(1, durableExecutions.get());
        assertEquals(OperationPhase.DURABLE, storedOperation().phase());

        RevisionConsumer recovered = new RevisionConsumer("profile_view", 0);
        OperationWorkflowResult second = execute(durableExecutions, recovered);

        assertEquals(OperationWorkflowResult.Status.PUBLISHED, second.status());
        assertEquals(OperationPhase.PUBLISHED, second.operation().phase());
        assertEquals(1, durableExecutions.get());
        assertEquals(1, recovered.applyCalls.get());

        OperationWorkflowResult repeated = execute(durableExecutions, recovered);
        assertEquals(OperationWorkflowResult.Status.PUBLISHED, repeated.status());
        assertEquals(1, repeated.events().size());
        assertEquals(1, durableExecutions.get());
        assertEquals(1, recovered.applyCalls.get());
    }

    @Test
    void requiredConsumersPublishConcurrentlyAndReturnFirstConcreteFailure()
            throws Exception {
        AtomicInteger durableExecutions = new AtomicInteger();
        BlockingConsumer first = new BlockingConsumer(
                "first_projection", true
        );
        BlockingConsumer second = new BlockingConsumer(
                "second_projection", false
        );

        SqliteDatabaseOperationCoordinator.Submission submission = submit(
                durableExecutions, List.of(first, second)
        );
        assertTrue(first.entered.await(10, TimeUnit.SECONDS));
        assertTrue(second.entered.await(10, TimeUnit.SECONDS));
        try {
            first.release.countDown();
            second.release.countDown();
            OperationWorkflowResult result = submission.completion()
                    .toCompletableFuture().get(10, TimeUnit.SECONDS);

            assertEquals(
                    OperationWorkflowResult.Status.PUBLICATION_PENDING,
                    result.status()
            );
            assertEquals("first projection failure", result.failure().getMessage());
            assertEquals(1, durableExecutions.get());
            assertEquals(1, first.applyCalls.get());
            assertEquals(1, second.applyCalls.get());
        } finally {
            first.release.countDown();
            second.release.countDown();
        }
    }

    @Test
    void boundLiveAndRecoveryConsumersUseIndependentPublisherLanes()
            throws Exception {
        AtomicInteger durableExecutions = new AtomicInteger();
        ContextBlockingConsumer delegate = new ContextBlockingConsumer(
                "contextual_projection"
        );
        ProjectionConsumer live = new ContextualProjectionConsumer(
                delegate, ProjectionPublicationContext.LIVE_COMMIT
        );
        ProjectionConsumer recovery = new ContextualProjectionConsumer(
                delegate, ProjectionPublicationContext.RECOVERY_CONVERGENCE
        );

        SqliteDatabaseOperationCoordinator.Submission liveSubmission =
                submitContextOperation(
                        durableExecutions,
                        OPERATION,
                        PROFILE,
                        live
                );
        assertTrue(delegate.entered(
                ProjectionPublicationContext.LIVE_COMMIT
        ).await(10, TimeUnit.SECONDS));

        SqliteDatabaseOperationCoordinator.Submission recoverySubmission =
                submitContextOperation(
                        durableExecutions,
                        CONTEXT_RECOVERY_OPERATION,
                        CONTEXT_RECOVERY_PROFILE,
                        recovery
                );
        assertTrue(delegate.entered(
                ProjectionPublicationContext.RECOVERY_CONVERGENCE
        ).await(10, TimeUnit.SECONDS));

        try {
            delegate.release(ProjectionPublicationContext.LIVE_COMMIT);
            delegate.release(ProjectionPublicationContext.RECOVERY_CONVERGENCE);
            assertEquals(
                    OperationWorkflowResult.Status.PUBLISHED,
                    liveSubmission.completion().toCompletableFuture()
                            .get(10, TimeUnit.SECONDS).status()
            );
            assertEquals(
                    OperationWorkflowResult.Status.PUBLISHED,
                    recoverySubmission.completion().toCompletableFuture()
                            .get(10, TimeUnit.SECONDS).status()
            );
            assertEquals(2, durableExecutions.get());
            assertEquals(2, delegate.maxConcurrent.get());
            assertEquals(
                    Set.of(
                            ProjectionPublicationContext.LIVE_COMMIT,
                            ProjectionPublicationContext.RECOVERY_CONVERGENCE
                    ),
                    Set.copyOf(delegate.contexts)
            );
        } finally {
            delegate.release(ProjectionPublicationContext.LIVE_COMMIT);
            delegate.release(ProjectionPublicationContext.RECOVERY_CONVERGENCE);
        }
    }

    private OperationWorkflowResult execute(
            AtomicInteger durableExecutions,
            ProjectionConsumer consumer
    ) throws Exception {
        return submit(durableExecutions, List.of(consumer)).completion()
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private SqliteDatabaseOperationCoordinator.Submission submit(
            AtomicInteger durableExecutions,
            List<? extends ProjectionConsumer> consumers
    ) {
        return coordinator.execute(
                definition,
                request(),
                (transaction, operation) -> {
                    durableExecutions.incrementAndGet();
                    createProfile(transaction);
                    return List.of(new ProjectionEventDraft(
                            operation.operationId(),
                            new ProjectionEventType("profile_created"),
                            PROFILE.toString(),
                            1,
                            1,
                            "{}",
                            -8_000
                    ));
                },
                consumers
        );
    }

    private SqliteDatabaseOperationCoordinator.Submission submitContextOperation(
            AtomicInteger durableExecutions,
            OperationId operationId,
            ProfileId profileId,
            ProjectionConsumer consumer
    ) {
        return coordinator.execute(
                definition,
                new OperationRequest<>(
                        operationId,
                        new IdempotencyKey("context-" + operationId),
                        new Payload("Contextual"),
                        "profile",
                        LifecycleRevision.INITIAL,
                        List.of(OperationScope.profile(profileId)),
                        -10_000
                ),
                (transaction, operation) -> {
                    durableExecutions.incrementAndGet();
                    return List.of(new ProjectionEventDraft(
                            operation.operationId(),
                            new ProjectionEventType("contextual_projection"),
                            profileId.toString(),
                            1,
                            1,
                            "{}",
                            -8_000
                    ));
                },
                List.of(consumer)
        );
    }

    private OperationRequest<Payload> request() {
        return new OperationRequest<>(
                OPERATION,
                new IdempotencyKey("profile-create-test"),
                new Payload("Companion"),
                "profile",
                LifecycleRevision.INITIAL,
                List.of(OperationScope.profile(PROFILE)),
                -10_000
        );
    }

    private void createProfile(SqlitePersistenceTransactionContext transaction) {
        transaction.identities().createProfile(new CompanionIdentity(
                PROFILE,
                "Companion",
                "role",
                null,
                null,
                "world",
                -10_000,
                -10_000,
                -10_000,
                0
        ));
        transaction.lifecycles().create(new CompanionLifecycle(
                PROFILE,
                null,
                LifecycleState.UNRESOLVED,
                LifecycleLocation.unresolved(),
                LifecycleRevision.INITIAL,
                null,
                -10_000,
                ReconciliationGeneration.INITIAL,
                null
        ));
    }

    private OperationEnvelope storedOperation() throws Exception {
        try (Connection connection = connections.openReadConnection()) {
            return new SqliteOperationStore(connection).find(OPERATION).orElseThrow();
        }
    }

    private record Payload(String name) {
    }

    private static final class TestDefinition implements OperationDefinition<Payload> {
        @Override
        public OperationKind kind() {
            return KIND;
        }

        @Override
        public int payloadVersion() {
            return 1;
        }

        @Override
        public Class<Payload> payloadType() {
            return Payload.class;
        }

        @Override
        public String encode(Payload payload) {
            return "{\"name\":\"" + payload.name() + "\"}";
        }

        @Override
        public Payload decode(String payloadJson) {
            return new Payload(payloadJson);
        }
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

    private static final class BlockingConsumer implements ProjectionConsumer {
        private final ProjectionConsumerId consumerId;
        private final boolean fail;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger applyCalls = new AtomicInteger();

        private BlockingConsumer(String consumerId, boolean fail) {
            this.consumerId = new ProjectionConsumerId(consumerId);
            this.fail = fail;
        }

        @Override
        public ProjectionConsumerId consumerId() {
            return consumerId;
        }

        @Override
        public ProjectionApplyOutcome apply(ProjectionEvent event)
                throws Exception {
            applyCalls.incrementAndGet();
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("projection consumer was not released");
            }
            if (fail) {
                throw new IllegalStateException("first projection failure");
            }
            return ProjectionApplyOutcome.APPLIED;
        }
    }

    private static final class ContextBlockingConsumer
            implements ProjectionConsumer {
        private final ProjectionConsumerId consumerId;
        private final Map<ProjectionPublicationContext, CountDownLatch> entered =
                new java.util.EnumMap<>(ProjectionPublicationContext.class);
        private final Map<ProjectionPublicationContext, CountDownLatch> releases =
                new java.util.EnumMap<>(ProjectionPublicationContext.class);
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxConcurrent = new AtomicInteger();
        private final List<ProjectionPublicationContext> contexts =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        private ContextBlockingConsumer(String consumerId) {
            this.consumerId = new ProjectionConsumerId(consumerId);
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
