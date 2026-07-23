package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.coop.CoopConflictDiagnostic;
import com.alechilles.alecstamework.companion.coop.CoopOccupancy;
import com.alechilles.alecstamework.companion.coop.CoopResidency;
import com.alechilles.alecstamework.companion.coop.CoopSlot;
import com.alechilles.alecstamework.companion.coop.CoopSlotKey;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotId;
import com.alechilles.alecstamework.companion.snapshot.SnapshotKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceMutationStatus;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transaction tests for coop slot, reservation, residency, and conflict authority. */
class SqliteCompanionCoopStoreTest {
    private static final ProfileId PROFILE_A =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final ProfileId PROFILE_B =
            ProfileId.parse("20000000-0000-0000-0000-000000000002");
    private static final OperationId OPERATION_A =
            OperationId.parse("40000000-0000-0000-0000-000000000001");
    private static final OperationId OPERATION_B =
            OperationId.parse("40000000-0000-0000-0000-000000000002");
    private static final CoopSlotKey SLOT_A =
            new CoopSlotKey("world", "coop", 10, 64, 20, 0);
    private static final CoopSlotKey SLOT_B =
            new CoopSlotKey("world", "coop", 10, 64, 20, 1);

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
    }

    @Test
    void claimAndReleaseKeepSlotAndResidentUniquenessAtomic() throws Exception {
        try (Connection connection = transaction()) {
            prepare(connection);
            SqliteCompanionCoopStore store = new SqliteCompanionCoopStore(connection);
            assertTrue(store.registerSlot(CoopSlot.unoccupied(SLOT_A)).applied());
            assertTrue(store.registerSlot(CoopSlot.unoccupied(SLOT_B)).applied());

            assertTrue(store.reserveEmpty(SLOT_A, PROFILE_A, OPERATION_A).applied());
            assertEquals(
                    PersistenceMutationStatus.FENCE_MISMATCH,
                    store.reserveEmpty(SLOT_A, PROFILE_B, OPERATION_B).status()
            );
            CoopResidency residency = residency(SLOT_A, PROFILE_A);
            CoopOccupancy occupied =
                    store.commitCapture(residency, OPERATION_A).value();

            assertEquals(1, occupied.slot().residencyRevision());
            assertFalse(occupied.slot().reserved());
            assertEquals(residency, store.findResidencyBySlot(SLOT_A).orElseThrow());
            assertEquals(residency, store.findResidencyByProfile(PROFILE_A).orElseThrow());
            assertEquals(
                    CoopConflictDiagnostic.Reason.SLOT_OCCUPIED,
                    store.diagnoseCapture(SLOT_A, PROFILE_B).reason()
            );
            assertEquals(
                    CoopConflictDiagnostic.Reason.PROFILE_ALREADY_RESIDENT,
                    store.diagnoseCapture(SLOT_B, PROFILE_A).reason()
            );

            assertTrue(store.reserveOccupied(SLOT_A, PROFILE_A, OPERATION_B).applied());
            CoopSlot released =
                    store.commitRelease(SLOT_A, PROFILE_A, OPERATION_B, -7_000).value();
            assertEquals(2, released.residencyRevision());
            assertFalse(released.reserved());
            assertTrue(store.findResidencyBySlot(SLOT_A).isEmpty());
            assertTrue(store.findAllOccupancies().isEmpty());
            connection.commit();
        }
    }

    @Test
    void diagnosticsDistinguishMissingEmptyOccupiedAndWrongResident() throws Exception {
        try (Connection connection = transaction()) {
            prepare(connection);
            SqliteCompanionCoopStore store = new SqliteCompanionCoopStore(connection);
            store.registerSlot(CoopSlot.unoccupied(SLOT_A));

            assertEquals(
                    CoopConflictDiagnostic.Reason.SLOT_MISSING,
                    store.diagnoseCapture(SLOT_B, PROFILE_A).reason()
            );
            assertEquals(
                    CoopConflictDiagnostic.Reason.SLOT_EMPTY,
                    store.diagnoseRelease(SLOT_A, PROFILE_A).reason()
            );
            store.reserveEmpty(SLOT_A, PROFILE_A, OPERATION_A);
            store.commitCapture(residency(SLOT_A, PROFILE_A), OPERATION_A);
            assertEquals(
                    CoopConflictDiagnostic.Reason.RESIDENT_MISMATCH,
                    store.diagnoseRelease(SLOT_A, PROFILE_B).reason()
            );
            connection.commit();
        }
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private void prepare(Connection connection) throws Exception {
        createProfile(connection, PROFILE_A);
        createProfile(connection, PROFILE_B);
        insertOperation(connection, OPERATION_A);
        insertOperation(connection, OPERATION_B);
        CompanionSnapshot snapshot = new CompanionSnapshot(
                new SnapshotId(UUID.fromString(
                        "50000000-0000-0000-0000-000000000001"
                )),
                PROFILE_A,
                new SnapshotKind("coop"),
                1,
                "{}",
                Sha256Hash.ofUtf8("{}"),
                LifecycleRevision.INITIAL,
                true,
                -9_000
        );
        new SqliteCompanionSnapshotStore(connection).replaceCurrent(snapshot);
    }

    private void createProfile(Connection connection, ProfileId profileId) {
        new SqliteCompanionIdentityStore(connection).createProfile(new CompanionIdentity(
                profileId, "Companion", "role", null, null, "world",
                -10_000, -10_000, -10_000, 0
        ));
        new SqliteCompanionLifecycleStore(connection).create(new CompanionLifecycle(
                profileId, null, LifecycleState.UNRESOLVED,
                LifecycleLocation.unresolved(), LifecycleRevision.INITIAL,
                null, -10_000, ReconciliationGeneration.INITIAL, null
        ));
    }

    private CoopResidency residency(CoopSlotKey key, ProfileId profileId) {
        return new CoopResidency(
                key,
                profileId,
                NpcAlias.parse("30000000-0000-0000-0000-000000000001"),
                new SnapshotId(UUID.fromString(
                        "50000000-0000-0000-0000-000000000001"
                )),
                -8_000,
                -8_000
        );
    }

    private void insertOperation(Connection connection, OperationId operationId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO operation_envelope(
                    operation_id, idempotency_key, operation_kind, payload_version,
                    payload_json, phase, feature_scope, expected_lifecycle_revision,
                    lease_owner, lease_until_ms, attempt_count, failure_kind, failure_code,
                    created_at_ms, updated_at_ms, durable_at_ms, published_at_ms, terminal_at_ms
                ) VALUES (?, ?, 'coop_test', 1, '{}', 'PREPARED', 'test',
                          NULL, NULL, 0, 0, NULL, NULL, -10000, -10000, NULL, NULL, NULL)
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, operationId.toString());
            statement.executeUpdate();
        }
    }
}
