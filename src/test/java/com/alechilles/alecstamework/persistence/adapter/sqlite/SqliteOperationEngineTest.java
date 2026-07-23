package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionAlias;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.operation.DurableCommitEvidence;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationRequest;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.PreparedOperationDetail;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end tests for staged canonical operation transactions and exact readback. */
class SqliteOperationEngineTest {
    private static final OperationKind KIND = new OperationKind("profile_create");
    private static final OperationId OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000001");
    private static final OperationId OTHER_OPERATION =
            OperationId.parse("40000000-0000-0000-0000-000000000002");
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final NpcAlias ALIAS =
            NpcAlias.parse("30000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private TestDefinition definition;
    private OperationDefinitionRegistry definitions;
    private SqliteOperationEngine engine;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        definition = new TestDefinition();
        definitions = new OperationDefinitionRegistry(List.of(definition));
        engine = engine(writer);
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
    void canonicalMutationDurablePhaseAndOutboxCommitTogether() throws Exception {
        OperationEnvelope prepared = committed(engine.prepare(definition, request()));
        OperationEnvelope applying = committed(engine.transition(
                prepared, OperationPhase.LIVE_APPLYING, null, null, -9_000
        ));
        DurableCommitEvidence durable = committed(engine.commitDurable(
                applying,
                (transaction, operation) -> {
                    createCanonicalProfile(transaction);
                    return List.of(event(operation, 0, -8_000));
                },
                -8_000
        ));

        assertEquals(OperationPhase.DURABLE, durable.operation().phase());
        assertEquals(1, durable.events().size());
        try (Connection connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            assertTrue(transaction.identities().findProfile(PROFILE).isPresent());
            assertTrue(transaction.lifecycles().findByProfile(PROFILE).isPresent());
            assertEquals(durable.events(),
                    transaction.outbox().findByOperation(OPERATION));
        }

        OperationEnvelope published = committed(engine.transition(
                durable.operation(), OperationPhase.PUBLISHED, null, null, -7_000
        ));
        assertEquals(OperationPhase.PUBLISHED, published.phase());
    }

    @Test
    void databaseOnlyOperationCommitsDirectlyFromPrepared() throws Exception {
        OperationEnvelope prepared = committed(engine.prepare(definition, request()));

        DurableCommitEvidence durable = committed(engine.commitDurable(
                prepared,
                (transaction, operation) -> {
                    createCanonicalProfile(transaction);
                    return List.of(event(operation, 0, -8_000));
                },
                -8_000
        ));

        assertEquals(OperationPhase.DURABLE, durable.operation().phase());
        assertEquals(1, durable.events().size());
    }

    @Test
    void typedPreparationDetailCommitsAtomicallyWithOperationFence()
            throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            createCanonicalProfile(
                    new SqlitePersistenceTransactionContext(connection)
            );
            connection.commit();
        }
        PreparedOperationDetail aliasLease = new PreparedOperationDetail() {
            @Override
            public void prepare(
                    SqlitePersistenceTransactionContext transaction,
                    OperationEnvelope operation
            ) {
                if (!transaction.identities().leaseAlias(
                        PROFILE,
                        ALIAS,
                        operation.operationId(),
                        -9_000
                ).applied()) {
                    throw new IllegalStateException("alias_lease_failed");
                }
            }

            @Override
            public boolean matches(
                    SqlitePersistenceTransactionContext transaction,
                    OperationEnvelope operation
            ) {
                CompanionAlias alias =
                        transaction.identities().resolveAlias(ALIAS).orElse(null);
                return alias != null
                        && alias.profileId().equals(PROFILE)
                        && operation.operationId().equals(alias.leaseOperationId())
                        && alias.state() != CompanionAlias.State.RETIRED;
            }
        };

        OperationEnvelope prepared = committed(
                engine.prepare(definition, request(), aliasLease)
        );

        assertEquals(OperationPhase.PREPARED, prepared.phase());
        try (Connection connection = connections.openReadConnection()) {
            CompanionAlias leased = new SqliteCompanionIdentityStore(connection)
                    .resolveAlias(ALIAS)
                    .orElseThrow();
            assertEquals(CompanionAlias.State.LEASED, leased.state());
            assertEquals(OPERATION, leased.leaseOperationId());
        }
    }

    @Test
    void preparationCheckpointFailureRollsBackOperationAndDetailTogether()
            throws Exception {
        try (Connection connection = connections.openWriterConnection()) {
            connection.setAutoCommit(false);
            createCanonicalProfile(
                    new SqlitePersistenceTransactionContext(connection)
            );
            connection.commit();
        }
        writer.shutdown(Duration.ofSeconds(5));
        writer = new SqliteSingleWriter(
                connections,
                SqliteWriterConfiguration.DEFAULT,
                (checkpoint, ignored) -> {
                    if (checkpoint
                            == com.alechilles.alecstamework.persistence.kernel
                            .PersistenceCheckpoint.BEFORE_COMMIT) {
                        throw new IllegalStateException("injected_before_prepare_commit");
                    }
                },
                PersistenceKernelMetrics.NO_OP
        );
        engine = engine(writer);
        PreparedOperationDetail aliasLease = new PreparedOperationDetail() {
            @Override
            public void prepare(
                    SqlitePersistenceTransactionContext transaction,
                    OperationEnvelope operation
            ) {
                if (!transaction.identities().leaseAlias(
                        PROFILE, ALIAS, operation.operationId(), -9_000
                ).applied()) {
                    throw new IllegalStateException("alias_lease_failed");
                }
            }

            @Override
            public boolean matches(
                    SqlitePersistenceTransactionContext transaction,
                    OperationEnvelope operation
            ) {
                return transaction.identities().resolveAlias(ALIAS).isPresent();
            }
        };

        PersistenceTransactionResult<OperationEnvelope> result =
                engine.prepare(definition, request(), aliasLease)
                        .completion().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);

        assertInstanceOf(PersistenceTransactionResult.RolledBack.class, result);
        try (Connection connection = connections.openReadConnection()) {
            assertTrue(new SqliteOperationStore(connection).find(OPERATION).isEmpty());
            assertTrue(new SqliteCompanionIdentityStore(connection)
                    .resolveAlias(ALIAS).isEmpty());
        }
    }

    @Test
    void unknownCommitReadbackNeverReexecutesDurableWork() throws Exception {
        writer.shutdown(Duration.ofSeconds(5));
        writer = new SqliteSingleWriter(
                connections,
                SqliteWriterConfiguration.DEFAULT,
                (checkpoint, ignored) -> {
                    if (checkpoint
                            == com.alechilles.alecstamework.persistence.kernel
                            .PersistenceCheckpoint.COMMIT_RETURNED) {
                        throw new IllegalStateException("injected_after_commit");
                    }
                },
                PersistenceKernelMetrics.NO_OP
        );
        engine = engine(writer);
        AtomicInteger durableExecutions = new AtomicInteger();
        OperationEnvelope prepared = committed(engine.prepare(definition, request()));
        OperationEnvelope applying = committed(engine.transition(
                prepared, OperationPhase.LIVE_APPLYING, null, null, -9_000
        ));

        DurableCommitEvidence durable = committed(engine.commitDurable(
                applying,
                (transaction, operation) -> {
                    durableExecutions.incrementAndGet();
                    createCanonicalProfile(transaction);
                    return List.of(event(operation, 0, -8_000));
                },
                -8_000
        ));

        assertEquals(1, durableExecutions.get());
        assertEquals(OperationPhase.DURABLE, durable.operation().phase());
        assertEquals(1, durable.events().size());
    }

    @Test
    void operationCheckpointFailureRollsBackEveryCanonicalParticipant() throws Exception {
        OperationEnvelope prepared = committed(engine.prepare(definition, request()));
        OperationEnvelope applying = committed(engine.transition(
                prepared, OperationPhase.LIVE_APPLYING, null, null, -9_000
        ));
        writer.shutdown(Duration.ofSeconds(5));
        writer = new SqliteSingleWriter(
                connections,
                SqliteWriterConfiguration.DEFAULT,
                (checkpoint, ignored) -> {
                    if (checkpoint
                            == com.alechilles.alecstamework.persistence.kernel
                            .PersistenceCheckpoint.BEFORE_COMMIT) {
                        throw new IllegalStateException("injected_before_commit");
                    }
                },
                PersistenceKernelMetrics.NO_OP
        );
        SqliteOperationEngine faulted = engine(writer);

        PersistenceTransactionResult<DurableCommitEvidence> result =
                faulted.commitDurable(
                        applying,
                        (transaction, operation) -> {
                            createCanonicalProfile(transaction);
                            return List.of(event(operation, 0, -8_000));
                        },
                        -8_000
                ).completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertInstanceOf(PersistenceTransactionResult.RolledBack.class, result);
        try (Connection connection = connections.openReadConnection()) {
            SqlitePersistenceTransactionContext transaction =
                    new SqlitePersistenceTransactionContext(connection);
            assertTrue(transaction.identities().findProfile(PROFILE).isEmpty());
            assertTrue(transaction.outbox().findByOperation(OPERATION).isEmpty());
            assertEquals(
                    OperationPhase.LIVE_APPLYING,
                    transaction.operations().find(OPERATION).orElseThrow().phase()
            );
        }
    }

    @Test
    void unknownContainmentBlocksNewScopeWorkButAllowsExactReadback()
            throws Exception {
        OperationEnvelope prepared = committed(
                engine.prepare(definition, request())
        );
        OperationEnvelope applying = committed(engine.transition(
                prepared,
                OperationPhase.LIVE_APPLYING,
                null,
                null,
                -9_500
        ));
        OperationEnvelope unknown = committed(engine.transition(
                applying,
                OperationPhase.UNKNOWN,
                "live",
                "receipt_read_failed",
                -9_000
        ));
        committed(engine.containUnknown(
                unknown,
                "receipt_read_failed",
                "Could not prove external mutation",
                List.of(
                        OperationScope.operation(OPERATION),
                        OperationScope.profile(PROFILE)
                ),
                -8_500
        ));

        OperationEnvelope exactReplay = committed(
                engine.prepare(definition, request())
        );
        assertEquals(OperationPhase.UNKNOWN, exactReplay.phase());

        PersistenceTransactionResult<OperationEnvelope> rejected =
                engine.prepare(definition, new OperationRequest<>(
                        OTHER_OPERATION,
                        new IdempotencyKey("other-profile-create-test"),
                        new Payload("Other"),
                        "profile",
                        LifecycleRevision.INITIAL,
                        List.of(OperationScope.profile(PROFILE)),
                        -8_000
                )).completion().toCompletableFuture()
                        .get(10, TimeUnit.SECONDS);

        assertInstanceOf(
                PersistenceTransactionResult.RolledBack.class,
                rejected
        );
        try (Connection connection = connections.openReadConnection()) {
            assertTrue(new SqliteOperationStore(connection)
                    .find(OTHER_OPERATION).isEmpty());
        }
    }

    private SqliteOperationEngine engine(SqliteSingleWriter writer) {
        return new SqliteOperationEngine(
                definitions,
                new SqliteUnitOfWorkRunner(writer, reads)
        );
    }

    private OperationRequest<Payload> request() {
        return new OperationRequest<>(
                OPERATION, new IdempotencyKey("profile-create-test"),
                new Payload("Companion"), "profile",
                LifecycleRevision.INITIAL, List.of(OperationScope.profile(PROFILE)), -10_000
        );
    }

    private void createCanonicalProfile(SqlitePersistenceTransactionContext transaction) {
        transaction.identities().createProfile(new CompanionIdentity(
                PROFILE, "Companion", "role", null, null, "world",
                -10_000, -10_000, -10_000, 0
        ));
        transaction.lifecycles().create(new CompanionLifecycle(
                PROFILE, null, LifecycleState.UNRESOLVED, LifecycleLocation.unresolved(),
                LifecycleRevision.INITIAL, null, -10_000,
                ReconciliationGeneration.INITIAL, null
        ));
    }

    private ProjectionEventDraft event(OperationEnvelope operation,
                                       long revision,
                                       long createdAtMs) {
        return new ProjectionEventDraft(
                operation.operationId(), new ProjectionEventType("profile_created"),
                PROFILE.toString(), revision, 1, "{}", createdAtMs
        );
    }

    @SuppressWarnings("unchecked")
    private <T> T committed(SqliteUnitOfWorkRunner.Submission<T> submission) throws Exception {
        PersistenceTransactionResult<T> result =
                submission.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
        PersistenceTransactionResult.Committed<T> committed = assertInstanceOf(
                PersistenceTransactionResult.Committed.class,
                result
        );
        return committed.value();
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
}
