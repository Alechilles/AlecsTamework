package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.incidents.QuarantineState;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationDefinition;
import com.alechilles.alecstamework.persistence.operation.OperationDefinitionRegistry;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationTransition;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventDraft;
import com.alechilles.alecstamework.persistence.projection.ProjectionEventType;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryAction;
import com.alechilles.alecstamework.persistence.recovery.OperationRecoveryScanResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests for bounded decode-first operation recovery and scoped containment. */
class SqliteOperationRecoveryCoordinatorTest {
    private static final OperationKind KIND = new OperationKind("recovery_test");
    private static final OperationId PREPARED =
            OperationId.parse("40000000-0000-0000-0000-000000000001");
    private static final OperationId APPLYING =
            OperationId.parse("40000000-0000-0000-0000-000000000002");
    private static final OperationId DURABLE =
            OperationId.parse("40000000-0000-0000-0000-000000000003");
    private static final OperationId UNKNOWN =
            OperationId.parse("40000000-0000-0000-0000-000000000004");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSingleWriter writer;
    private SqliteReadExecutor reads;
    private OperationDefinitionRegistry definitions;
    private SqliteOperationRecoveryCoordinator recovery;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
        writer = new SqliteSingleWriter(connections);
        reads = new SqliteReadExecutor(connections);
        definitions = new OperationDefinitionRegistry(List.of(new TestDefinition()));
        recovery = new SqliteOperationRecoveryCoordinator(
                definitions,
                reads,
                new SqliteUnitOfWorkRunner(writer, reads)
        );
    }

    @AfterEach
    void tearDown() {
        writer.shutdown(Duration.ofSeconds(5));
        reads.shutdown(Duration.ofSeconds(5));
    }

    @Test
    void mapsDurablePhasesToEvidenceDrivenLeasedActions() throws Exception {
        createPhaseFixtures();

        OperationRecoveryScanResult result = recovery.scanAndClaim(
                "worker-a", -9_000, -8_000, 10
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);
        Map<OperationId, OperationRecoveryAction> actions = result.claims().stream()
                .collect(Collectors.toMap(
                        claim -> claim.operation().operationId(),
                        claim -> claim.action()
                ));

        assertEquals(OperationRecoveryScanResult.Status.COMPLETE, result.status());
        assertEquals(OperationRecoveryAction.RESUME_LIVE_APPLY, actions.get(PREPARED));
        assertEquals(OperationRecoveryAction.VERIFY_LIVE_APPLY, actions.get(APPLYING));
        assertEquals(OperationRecoveryAction.PUBLISH_DURABLE, actions.get(DURABLE));
        assertTrue(result.issues().isEmpty());
        assertTrue(result.claims().stream()
                .allMatch(claim -> "worker-a".equals(claim.operation().leaseOwner())));

        assertTrue(recovery.scanAndClaim("worker-b", -8_500, -7_500, 10)
                .toCompletableFuture().get(10, TimeUnit.SECONDS).claims().isEmpty());
        OperationRecoveryScanResult reclaimed = recovery.scanAndClaim(
                "worker-b", -7_000, -6_000, 10
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(3, reclaimed.claims().size());
        assertTrue(reclaimed.claims().stream()
                .allMatch(claim -> claim.operation().attemptCount() == 2));
    }

    @Test
    void undecodableOperationIsQuarantinedWithoutStarvingLaterWork() throws Exception {
        try (Connection connection = transaction()) {
            prepare(connection, UNKNOWN, new OperationKind("missing_definition"), -10_000);
            prepare(connection, PREPARED, KIND, -9_000);
            connection.commit();
        }

        OperationRecoveryScanResult first = recovery.scanAndClaim(
                "worker", -8_000, -7_000, 1
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(1, first.issues().size());
        assertTrue(first.issues().getFirst().contained());
        assertTrue(first.claims().isEmpty());

        try (Connection connection = connections.openReadConnection()) {
            assertEquals(
                    QuarantineState.ACTIVE,
                    new SqliteIncidentStore(connection)
                            .findQuarantine(OperationScope.operation(UNKNOWN))
                            .orElseThrow()
                            .state()
            );
        }

        OperationRecoveryScanResult second = recovery.scanAndClaim(
                "worker", -8_000, -7_000, 1
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertEquals(List.of(PREPARED), second.claims().stream()
                .map(claim -> claim.operation().operationId())
                .toList());
        OperationRecoveryScanResult third = recovery.scanAndClaim(
                "worker", -8_000, -7_000, 10
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);
        assertTrue(third.claims().isEmpty());
        assertTrue(third.issues().isEmpty());
    }

    @Test
    void currentRunExclusionsDoNotStarveLaterRecoverableWork()
            throws Exception {
        try (Connection connection = transaction()) {
            prepare(connection, PREPARED, KIND, -10_000);
            prepare(connection, APPLYING, KIND, -9_000);
            connection.commit();
        }

        OperationRecoveryScanResult result = recovery.scanAndClaim(
                "worker",
                -8_000,
                -7_000,
                1,
                Set.of(PREPARED)
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(
                List.of(APPLYING),
                result.claims().stream()
                        .map(claim -> claim.operation().operationId())
                        .toList()
        );
    }

    @Test
    void unknownLeaseCommitUsesExactReadbackWithoutIncrementingTwice() throws Exception {
        try (Connection connection = transaction()) {
            prepare(connection, PREPARED, KIND, -10_000);
            connection.commit();
        }
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
        recovery = new SqliteOperationRecoveryCoordinator(
                definitions, reads, new SqliteUnitOfWorkRunner(writer, reads)
        );

        OperationRecoveryScanResult result = recovery.scanAndClaim(
                "worker", -9_000, -8_000, 10
        ).toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(1, result.claims().size());
        assertEquals(1, result.claims().getFirst().operation().attemptCount());
        try (Connection connection = connections.openReadConnection()) {
            assertEquals(
                    1,
                    new SqliteOperationStore(connection).find(PREPARED)
                            .orElseThrow().attemptCount()
            );
        }
    }

    private void createPhaseFixtures() throws Exception {
        try (Connection connection = transaction()) {
            prepare(connection, PREPARED, KIND, -10_000);
            OperationEnvelope applyingPrepared = prepare(
                    connection, APPLYING, KIND, -9_900
            );
            new SqliteOperationStore(connection).transition(new OperationTransition(
                    APPLYING, OperationPhase.PREPARED, OperationPhase.LIVE_APPLYING,
                    null, null, null, -9_800
            ));
            OperationEnvelope durablePrepared = prepare(
                    connection, DURABLE, KIND, -9_700
            );
            SqliteOperationStore operations = new SqliteOperationStore(connection);
            operations.transition(new OperationTransition(
                    durablePrepared.operationId(), OperationPhase.PREPARED,
                    OperationPhase.LIVE_APPLYING, null, null, null, -9_600
            ));
            new SqliteProjectionOutboxStore(connection).append(new ProjectionEventDraft(
                    DURABLE, new ProjectionEventType("recovery_ready"),
                    "aggregate", 1, 1, "{}", -9_500
            ));
            operations.transition(new OperationTransition(
                    DURABLE, OperationPhase.LIVE_APPLYING, OperationPhase.DURABLE,
                    null, null, null, -9_400
            ));
            assertEquals(APPLYING, applyingPrepared.operationId());
            connection.commit();
        }
    }

    private OperationEnvelope prepare(Connection connection,
                                      OperationId operationId,
                                      OperationKind kind,
                                      long createdAtMs) {
        return new SqliteOperationStore(connection).prepare(new PreparedOperation(
                operationId, new IdempotencyKey("key-" + operationId),
                kind, 1, "{\"value\":\"test\"}", "test",
                null, List.of(), createdAtMs
        )).value();
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private record Payload(String value) {
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
            return "{\"value\":\"" + payload.value() + "\"}";
        }

        @Override
        public Payload decode(String payloadJson) {
            if (!payloadJson.contains("\"value\"")) {
                throw new IllegalArgumentException("invalid payload");
            }
            return new Payload(payloadJson);
        }
    }
}
