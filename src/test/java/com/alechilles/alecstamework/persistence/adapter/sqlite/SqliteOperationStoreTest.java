package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.OperationEnvelope;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.alechilles.alecstamework.persistence.operation.OperationLeaseRequest;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationScope;
import com.alechilles.alecstamework.persistence.operation.OperationTransition;
import com.alechilles.alecstamework.persistence.operation.PreparedOperation;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transaction integration tests for the one shared replacement operation protocol. */
class SqliteOperationStoreTest {
    private static final OperationKind KIND = new OperationKind("capture");
    private static final OperationId OPERATION_A =
            OperationId.parse("40000000-0000-0000-0000-000000000001");
    private static final OperationId OPERATION_B =
            OperationId.parse("40000000-0000-0000-0000-000000000002");
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
    }

    @Test
    void preparesOneIdempotentEnvelopeAndAllParticipantScopes() throws Exception {
        try (Connection connection = transaction()) {
            SqliteOperationStore store = new SqliteOperationStore(connection);
            PreparedOperation request = prepared(OPERATION_A, "same-key", "{\"value\":1}");
            OperationEnvelope first = store.prepare(request).value();
            OperationEnvelope replay = store.prepare(
                    prepared(OPERATION_B, "same-key", "{\"value\":1}")
            ).value();

            assertEquals(OPERATION_A, replay.operationId());
            assertEquals(first, replay);
            assertTrue(first.participants().contains(OperationScope.operation(OPERATION_A)));
            assertTrue(first.participants().stream()
                    .anyMatch(scope -> scope.type().name().equals("FEATURE")));
            assertEquals(
                    PersistenceMutationStatus.CONFLICT,
                    store.prepare(prepared(OPERATION_B, "same-key", "{\"value\":2}")).status()
            );
            connection.commit();
        }
    }

    @Test
    void enforcesTheSharedPhaseGraphAndCompletionEvidence() throws Exception {
        try (Connection connection = transaction()) {
            SqliteOperationStore store = new SqliteOperationStore(connection);
            store.prepare(prepared(OPERATION_A, "phase-key", "{}"));

            OperationEnvelope applying = store.transition(new OperationTransition(
                    OPERATION_A, OperationPhase.PREPARED, OperationPhase.LIVE_APPLYING,
                    null, null, null, -9_000
            )).value();
            assertEquals(OperationPhase.LIVE_APPLYING, applying.phase());
            assertEquals(
                    PersistenceMutationStatus.PHASE_MISMATCH,
                    store.transition(new OperationTransition(
                            OPERATION_A, OperationPhase.PREPARED, OperationPhase.FAILED,
                            null, "TEST", "stale", -8_500
                    )).status()
            );

            OperationEnvelope durable = store.transition(new OperationTransition(
                    OPERATION_A, OperationPhase.LIVE_APPLYING, OperationPhase.DURABLE,
                    null, null, null, -8_000
            )).value();
            assertEquals(-8_000, durable.durableAtMs());
            OperationEnvelope published = store.transition(new OperationTransition(
                    OPERATION_A, OperationPhase.DURABLE, OperationPhase.PUBLISHED,
                    null, null, null, -7_000
            )).value();
            assertEquals(-7_000, published.publishedAtMs());
            assertEquals(-7_000, published.terminalAtMs());
            assertTrue(published.phase().isTerminal());
            assertTrue(store.findRecoverable(-6_000, 10).isEmpty());
            connection.commit();
        }
    }

    @Test
    void leasesRecoverableWorkWithExpiryAndOwnerFencing() throws Exception {
        try (Connection connection = transaction()) {
            SqliteOperationStore store = new SqliteOperationStore(connection);
            store.prepare(prepared(OPERATION_A, "lease-key", "{}"));
            assertEquals(List.of(OPERATION_A), store.findRecoverable(-9_000, 10).stream()
                    .map(OperationEnvelope::operationId)
                    .toList());

            OperationEnvelope firstLease = store.acquireLease(new OperationLeaseRequest(
                    OPERATION_A, "worker-a", -9_000, -8_000
            )).value();
            assertEquals(1, firstLease.attemptCount());
            assertEquals(
                    PersistenceMutationStatus.FENCE_MISMATCH,
                    store.acquireLease(new OperationLeaseRequest(
                            OPERATION_A, "worker-b", -8_500, -7_500
                    )).status()
            );
            OperationEnvelope renewal = store.acquireLease(new OperationLeaseRequest(
                    OPERATION_A, "worker-a", -8_500, -7_000
            )).value();
            assertEquals(1, renewal.attemptCount());
            OperationEnvelope takeover = store.acquireLease(new OperationLeaseRequest(
                    OPERATION_A, "worker-b", -6_500, -5_500
            )).value();
            assertEquals(2, takeover.attemptCount());

            assertEquals(
                    PersistenceMutationStatus.FENCE_MISMATCH,
                    store.transition(new OperationTransition(
                            OPERATION_A, OperationPhase.PREPARED, OperationPhase.LIVE_APPLYING,
                            "worker-a", null, null, -6_000
                    )).status()
            );
            assertTrue(store.transition(new OperationTransition(
                    OPERATION_A, OperationPhase.PREPARED, OperationPhase.LIVE_APPLYING,
                    "worker-b", null, null, -6_000
            )).applied());
            connection.commit();
        }
    }

    @Test
    void storeNeverCommitsBehindItsOwningTransaction() throws Exception {
        try (Connection connection = transaction()) {
            assertTrue(new SqliteOperationStore(connection)
                    .prepare(prepared(OPERATION_A, "rollback-key", "{}"))
                    .applied());
            connection.rollback();
        }
        try (Connection connection = connections.openReadConnection()) {
            assertFalse(new SqliteOperationStore(connection).find(OPERATION_A).isPresent());
        }
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private PreparedOperation prepared(OperationId operationId,
                                       String idempotencyKey,
                                       String payload) {
        return new PreparedOperation(
                operationId, new IdempotencyKey(idempotencyKey), KIND, 1, payload,
                "capture", LifecycleRevision.INITIAL,
                List.of(OperationScope.profile(PROFILE)), -10_000
        );
    }
}
