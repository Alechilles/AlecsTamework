package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.operation.DatabaseOperationResult;
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
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumer;
import com.alechilles.alecstamework.persistence.projection.ProjectionConsumerId;
import com.alechilles.alecstamework.persistence.projection.ProjectionCoordinator;
import com.alechilles.alecstamework.persistence.projection.ProjectionEvent;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.alechilles.alecstamework.persistence.projection.ProjectionRetryPolicy;
import java.nio.file.Path;
import java.sql.Connection;
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

/** End-to-end tests for the one database-only operation workflow. */
class SqliteDatabaseOperationCoordinatorTest {
    private static final OperationKind KIND = new OperationKind("profile_create");
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000001");
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");

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

        DatabaseOperationResult result = execute(new AtomicInteger(), consumer);

        assertEquals(DatabaseOperationResult.Status.PUBLISHED, result.status());
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

        DatabaseOperationResult first = execute(durableExecutions, failing);

        assertEquals(DatabaseOperationResult.Status.PUBLICATION_PENDING, first.status());
        assertEquals(OperationPhase.DURABLE, first.operation().phase());
        assertEquals(1, durableExecutions.get());
        assertEquals(OperationPhase.DURABLE, storedOperation().phase());

        RevisionConsumer recovered = new RevisionConsumer("profile_view", 0);
        DatabaseOperationResult second = execute(durableExecutions, recovered);

        assertEquals(DatabaseOperationResult.Status.PUBLISHED, second.status());
        assertEquals(OperationPhase.PUBLISHED, second.operation().phase());
        assertEquals(1, durableExecutions.get());
        assertEquals(1, recovered.applyCalls.get());

        DatabaseOperationResult repeated = execute(durableExecutions, recovered);
        assertEquals(DatabaseOperationResult.Status.PUBLISHED, repeated.status());
        assertEquals(1, repeated.events().size());
        assertEquals(1, durableExecutions.get());
        assertEquals(1, recovered.applyCalls.get());
    }

    private DatabaseOperationResult execute(
            AtomicInteger durableExecutions,
            ProjectionConsumer consumer
    ) throws Exception {
        SqliteDatabaseOperationCoordinator.Submission submission = coordinator.execute(
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
                List.of(consumer)
        );
        assertNotNull(submission.acceptance());
        return submission.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
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
}
