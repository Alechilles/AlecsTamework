package com.alechilles.alecstamework.companion.population.domain;

import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationEngine;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteOperationStore;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteReadExecutor;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSingleWriter;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteUnitOfWorkRunner;
import com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpoint;
import com.alechilles.alecstamework.persistence.kernel.PersistenceCheckpointHook;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.operation.DecodedOperationPayload;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationTransition;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryAction;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryClaim;
import com.alechilles.alecstamework.persistence.runtime.PersistenceBootstrap;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceLiveBoundaries;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceRuntimeConfiguration;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceWorldReconciliation;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies expiry-gated admission recovery and fail-closed containment. */
class PopulationDomainAdmissionRecoveryTest {
    private static final OperationId OPERATION = OperationId.parse(
            "70000000-0000-0000-0000-000000000701"
    );
    private static final ProfileId PROFILE = ProfileId.parse(
            "70000000-0000-0000-0000-000000000702"
    );
    private static final OwnerId OWNER = new OwnerId(
            java.util.UUID.fromString("70000000-0000-0000-0000-000000000705")
    );

    @TempDir
    Path tempDir;

    @Test
    void unexpiredPreparedRecoveryDefersWithoutChangingTheOperation() {
        AtomicInteger now = new AtomicInteger(10);
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(
                configuration(tempDir.resolve("unexpired"), now::get))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            PopulationDomainAdmissionOperation operations = persistence.facades()
                    .operations().populationDomainAdmission();
            PopulationDomainAdmissionOperation.Payload payload = payload(100);
            OperationEnvelope prepared = committedEnvelope(operations.prepare(
                    OPERATION,
                    new IdempotencyKey("recovery-unexpired"),
                    payload
            ).completion().toCompletableFuture().join());
            assertNotNull(prepared);

            OperationWorkflowResult result = operations.recover(
                    claim(prepared, payload)
            ).toCompletableFuture().join();

            assertEquals(OperationWorkflowResult.Status.LIVE_RETRYABLE,
                    result.status());
            assertEquals(OperationPhase.PREPARED, result.operation().phase());
            OperationEnvelope unchanged = operations.findByIdempotency(
                    new IdempotencyKey("recovery-unexpired")
            ).toCompletableFuture().join().orElseThrow();
            assertEquals(OperationPhase.PREPARED, unchanged.phase());
        }
    }

    @Test
    void expiredPreparedRecoveryCancelsAfterDurableReadback() {
        AtomicInteger now = new AtomicInteger(10);
        try (PersistenceBootstrap persistence = new PersistenceBootstrap(
                configuration(tempDir.resolve("expired"), now::get))) {
            assertTrue(persistence.start().toCompletableFuture().join().complete());
            PopulationDomainAdmissionOperation operations = persistence.facades()
                    .operations().populationDomainAdmission();
            PopulationDomainAdmissionOperation.Payload payload = payload(10);
            OperationEnvelope prepared = committedEnvelope(operations.prepare(
                    OPERATION,
                    new IdempotencyKey("recovery-expired"),
                    payload
            ).completion().toCompletableFuture().join());

            OperationWorkflowResult result = operations.recover(
                    claim(prepared, payload)
            ).toCompletableFuture().join();

            assertEquals(OperationWorkflowResult.Status.PUBLISHED,
                    result.status());
            assertEquals(OperationPhase.PUBLISHED, result.operation().phase());
            assertTrue(operations.settlementEvidence(OPERATION)
                    .toCompletableFuture().join().canceled());
        }
    }

    @Test
    void committedContainmentReportsUnknownOnlyAfterBothReadbacks()
            throws Exception {
        try (RawFixture fixture = new RawFixture(tempDir.resolve("contained"),
                PersistenceCheckpointHook.NO_OP, false)) {
            OperationWorkflowResult result = PopulationDomainAdmissionRecovery
                    .contain(fixture.engine, fixture.operation, () -> 20)
                    .toCompletableFuture().join();

            assertEquals(OperationWorkflowResult.Status.LIVE_UNKNOWN,
                    result.status());
            assertEquals(OperationPhase.UNKNOWN, fixture.read().phase());
        }
    }

    @Test
    void rolledBackTransitionAndContainmentReadbackRemainRetryable()
            throws Exception {
        try (RawFixture transitionFailure = new RawFixture(
                tempDir.resolve("transition-failure"),
                (checkpoint, ignored) -> {
                    if (checkpoint == PersistenceCheckpoint.BEFORE_COMMIT) {
                        throw new IllegalStateException("transition-failure");
                    }
                }, false)) {
            OperationWorkflowResult result = PopulationDomainAdmissionRecovery
                    .contain(transitionFailure.engine, transitionFailure.operation, () -> 20)
                    .toCompletableFuture().join();

            assertEquals(OperationWorkflowResult.Status.LIVE_RETRYABLE,
                    result.status());
            assertEquals(OperationPhase.LIVE_APPLYING,
                    transitionFailure.read().phase());
        }

        AtomicInteger commits = new AtomicInteger();
        try (RawFixture containmentFailure = new RawFixture(
                tempDir.resolve("containment-failure"),
                (checkpoint, ignored) -> {
                    if (checkpoint == PersistenceCheckpoint.BEFORE_COMMIT
                            && commits.incrementAndGet() == 2) {
                        throw new IllegalStateException("containment-failure");
                    }
                }, false)) {
            OperationWorkflowResult result = PopulationDomainAdmissionRecovery
                    .contain(containmentFailure.engine, containmentFailure.operation, () -> 20)
                    .toCompletableFuture().join();

            assertEquals(OperationWorkflowResult.Status.LIVE_RETRYABLE,
                    result.status());
            assertEquals(OperationPhase.UNKNOWN, containmentFailure.read().phase());
        }

        try (RawFixture unknownContainment = new RawFixture(
                tempDir.resolve("unknown-containment"),
                (checkpoint, ignored) -> {
                    if (checkpoint == PersistenceCheckpoint.COMMIT_RETURNED) {
                        throw new IllegalStateException("unknown-containment");
                    }
                }, true)) {
            unknownContainment.reads.shutdown(Duration.ofSeconds(5));
            OperationWorkflowResult result = PopulationDomainAdmissionRecovery
                    .contain(unknownContainment.engine, unknownContainment.operation, () -> 20)
                    .toCompletableFuture().join();

            assertEquals(OperationWorkflowResult.Status.LIVE_RETRYABLE,
                    result.status());
            assertEquals(OperationPhase.UNKNOWN, unknownContainment.read().phase());
        }
    }

    private OperationRecoveryClaim claim(
            OperationEnvelope prepared,
            PopulationDomainAdmissionOperation.Payload payload
    ) {
        OperationEnvelope leased = new OperationEnvelope(
                prepared.operationId(),
                prepared.idempotencyKey(),
                prepared.kind(),
                prepared.payloadVersion(),
                prepared.payloadJson(),
                prepared.phase(),
                prepared.featureScope(),
                prepared.expectedLifecycleRevision(),
                "recovery-test-worker",
                100,
                prepared.attemptCount(),
                prepared.failureKind(),
                prepared.failureCode(),
                prepared.createdAtMs(),
                prepared.updatedAtMs(),
                prepared.durableAtMs(),
                prepared.publishedAtMs(),
                prepared.terminalAtMs(),
                prepared.participants()
        );
        return new OperationRecoveryClaim(
                leased,
                new DecodedOperationPayload(
                        PopulationDomainAdmissionDefinition.INSTANCE,
                        payload
                ),
                OperationRecoveryAction.RESUME_LIVE_APPLY
        );
    }

    private OperationEnvelope committedEnvelope(
            com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult<
                    OperationEnvelope> result
    ) {
        assertTrue(result instanceof com.alechilles.alecstamework.persistence.kernel
                .PersistenceTransactionResult.Committed<?>);
        return ((com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult
                .Committed<OperationEnvelope>) result).value();
    }

    private PopulationDomainAdmissionOperation.Payload payload(long expiresAtMs) {
        return new PopulationDomainAdmissionOperation.Payload(
                java.util.UUID.fromString("70000000-0000-0000-0000-000000000703"),
                PROFILE,
                OWNER,
                null,
                null,
                null,
                null,
                null,
                LifecycleState.ACTIVE,
                "recovery-test-group",
                "recovery-test-provider",
                1,
                "generation",
                1,
                1,
                expiresAtMs,
                1,
                List.of(),
                List.of(),
                1
        );
    }

    private PublicPersistenceRuntimeConfiguration configuration(
            Path dataDirectory,
            java.util.function.LongSupplier clock
    ) {
        return new PublicPersistenceRuntimeConfiguration(
                dataDirectory,
                "recovery-test",
                clock,
                (claim, operation) ->
                        com.alechilles.alecstamework.persistence.operation.LiveOperationResult
                                .confirmed("refund").completed(),
                event -> { },
                new PublicPersistenceLiveBoundaries(
                        (request, operation) -> confirmed("capture"),
                        (request, operation) -> confirmed("capture_release"),
                        (request, operation) -> confirmed("restoration"),
                        (request, operation) -> confirmed("coop_capture"),
                        (request, operation) -> confirmed("coop_release")
                ),
                PublicPersistenceWorldReconciliation.alreadyComplete(),
                Duration.ofSeconds(5)
        );
    }

    private java.util.concurrent.CompletionStage<
            com.alechilles.alecstamework.persistence.operation.LiveOperationResult>
    confirmed(String code) {
        return com.alechilles.alecstamework.persistence.operation.LiveOperationResult
                .confirmed(code).completed();
    }

    private static final class RawFixture implements AutoCloseable {
        private final SqliteConnectionFactory connections;
        private final SqliteSingleWriter writer;
        private final SqliteReadExecutor reads;
        private final SqliteOperationEngine engine;
        private final OperationEnvelope operation;

        private RawFixture(
                Path database,
                PersistenceCheckpointHook checkpoints,
                boolean unknown
        ) throws Exception {
            connections = new SqliteConnectionFactory(database);
            new SqliteSchemaV1Manager(connections, () -> 0).initialize();
            operation = createOperation(unknown);
            reads = new SqliteReadExecutor(connections);
            writer = new SqliteSingleWriter(
                    connections,
                    com.alechilles.alecstamework.persistence.adapter.sqlite
                            .SqliteWriterConfiguration.DEFAULT,
                    checkpoints,
                    PersistenceKernelMetrics.NO_OP
            );
            SqliteUnitOfWorkRunner units = new SqliteUnitOfWorkRunner(
                    writer, reads
            );
            engine = new SqliteOperationEngine(
                    new OperationDefinitionRegistry(List.of(
                            PopulationDomainAdmissionDefinition.INSTANCE
                    )),
                    units
            );
        }

        private OperationEnvelope createOperation(boolean unknown) throws Exception {
            try (java.sql.Connection connection = connections.openWriterConnection()) {
                connection.setAutoCommit(false);
                SqliteOperationStore store = new SqliteOperationStore(connection);
                OperationId id = OperationId.parse(
                        "70000000-0000-0000-0000-000000000704"
                );
                OperationEnvelope prepared = store.prepare(new PreparedOperation(
                        id,
                        new IdempotencyKey("raw-recovery"),
                        PopulationDomainAdmissionDefinition.KIND,
                        1,
                        "{}",
                        PopulationDomainAdmissionOperation.FEATURE_SCOPE,
                        null,
                        List.of(OperationScope.operation(id)),
                        1
                )).value();
                OperationEnvelope applying = store.transition(new OperationTransition(
                        id,
                        OperationPhase.PREPARED,
                        OperationPhase.LIVE_APPLYING,
                        null,
                        null,
                        null,
                        2
                )).value();
                OperationEnvelope current = applying;
                if (unknown) {
                    current = store.transition(new OperationTransition(
                            id,
                            OperationPhase.LIVE_APPLYING,
                            OperationPhase.UNKNOWN,
                            null,
                            "LIVE_OUTCOME_UNKNOWN",
                            "raw_unknown",
                            3
                    )).value();
                }
                connection.commit();
                return current;
            }
        }

        private OperationEnvelope read() throws Exception {
            try (java.sql.Connection connection = connections.openReadConnection()) {
                return new SqliteOperationStore(connection)
                        .find(operation.operationId()).orElseThrow();
            }
        }

        @Override
        public void close() {
            writer.shutdown(Duration.ofSeconds(5));
            reads.shutdown(Duration.ofSeconds(5));
        }
    }
}
