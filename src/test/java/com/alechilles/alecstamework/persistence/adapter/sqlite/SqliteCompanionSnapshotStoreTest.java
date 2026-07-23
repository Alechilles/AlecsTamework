package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
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
import com.alechilles.alecstamework.persistence.kernel.PersistenceStoreException;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transaction integration tests for replacement snapshot history and revision correlation. */
class SqliteCompanionSnapshotStoreTest {
    private static final ProfileId PROFILE =
            ProfileId.parse("20000000-0000-0000-0000-000000000001");
    private static final SnapshotKind KIND = new SnapshotKind("capture");
    private static final SnapshotId SNAPSHOT_A =
            new SnapshotId(UUID.fromString("40000000-0000-0000-0000-000000000001"));
    private static final SnapshotId SNAPSHOT_B =
            new SnapshotId(UUID.fromString("40000000-0000-0000-0000-000000000002"));

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        new SqliteSchemaV1Manager(connections, () -> -10_000).initialize();
    }

    @Test
    void replacesOnlyTheCurrentMarkerAndPreservesHistory() throws Exception {
        try (Connection connection = transaction()) {
            createProfileAndLifecycle(connection);
            SqliteCompanionSnapshotStore store = new SqliteCompanionSnapshotStore(connection);
            CompanionSnapshot first = snapshot(SNAPSHOT_A, "{\"generation\":1}", 0, -9_000);
            CompanionSnapshot second = snapshot(SNAPSHOT_B, "{\"generation\":2}", 0, -8_000);

            assertTrue(store.replaceCurrent(first).applied());
            assertTrue(store.replaceCurrent(second).applied());
            assertEquals(second, store.findCurrent(PROFILE, KIND).orElseThrow());
            assertEquals(java.util.List.of(second), store.findCurrentByProfile(PROFILE));
            assertEquals(2, store.findHistory(PROFILE, KIND).size());
            assertFalse(store.findById(SNAPSHOT_A).orElseThrow().current());
            assertTrue(store.findById(SNAPSHOT_B).orElseThrow().current());
            connection.commit();
        }
    }

    @Test
    void rejectsLifecycleRevisionMismatchAndSnapshotIdConflict() throws Exception {
        try (Connection connection = transaction()) {
            createProfileAndLifecycle(connection);
            SqliteCompanionSnapshotStore store = new SqliteCompanionSnapshotStore(connection);
            assertEquals(
                    PersistenceMutationStatus.REVISION_MISMATCH,
                    store.replaceCurrent(snapshot(
                            SNAPSHOT_A, "{\"generation\":1}", 1, -9_000
                    )).status()
            );
            assertTrue(store.replaceCurrent(snapshot(
                    SNAPSHOT_A, "{\"generation\":1}", 0, -9_000
            )).applied());
            assertEquals(
                    PersistenceMutationStatus.CONFLICT,
                    store.replaceCurrent(snapshot(
                            SNAPSHOT_A, "{\"generation\":2}", 0, -8_000
                    )).status()
            );
            connection.commit();
        }
    }

    @Test
    void corruptOrInvalidPayloadIsAnExplicitFailureNotAbsence() throws Exception {
        try (Connection connection = transaction()) {
            createProfileAndLifecycle(connection);
            SqliteCompanionSnapshotStore store = new SqliteCompanionSnapshotStore(connection);
            CompanionSnapshot invalidJson = snapshot(SNAPSHOT_A, "not-json", 0, -9_000);
            assertThrows(PersistenceStoreException.class, () -> store.replaceCurrent(invalidJson));

            CompanionSnapshot valid = snapshot(SNAPSHOT_A, "{}", 0, -9_000);
            assertTrue(store.replaceCurrent(valid).applied());
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE companion_snapshot SET payload_hash = ? WHERE snapshot_id = ?
                    """)) {
                statement.setString(1, "0".repeat(64));
                statement.setString(2, SNAPSHOT_A.toString());
                statement.executeUpdate();
            }
            assertThrows(PersistenceStoreException.class, () -> store.findById(SNAPSHOT_A));
            connection.rollback();
        }
    }

    @Test
    void retiresOneExactCurrentSnapshotIdempotently() throws Exception {
        try (Connection connection = transaction()) {
            createProfileAndLifecycle(connection);
            SqliteCompanionSnapshotStore store =
                    new SqliteCompanionSnapshotStore(connection);
            CompanionSnapshot snapshot = snapshot(
                    SNAPSHOT_A,
                    "{\"generation\":1}",
                    0,
                    -9_000
            );
            assertTrue(store.replaceCurrent(snapshot).applied());

            CompanionSnapshot retired = store.retireCurrent(
                    snapshot.snapshotId()
            ).value();

            assertFalse(retired.current());
            assertFalse(store.findById(snapshot.snapshotId())
                    .orElseThrow()
                    .current());
            assertTrue(store.retireCurrent(snapshot.snapshotId()).applied());
            connection.commit();
        }
    }

    private Connection transaction() throws Exception {
        Connection connection = connections.openWriterConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private void createProfileAndLifecycle(Connection connection) {
        new SqliteCompanionIdentityStore(connection).createProfile(new CompanionIdentity(
                PROFILE, "Companion", "role", null, null, "world",
                -10_000, -10_000, -10_000, 0
        ));
        new SqliteCompanionLifecycleStore(connection).create(new CompanionLifecycle(
                PROFILE, null, LifecycleState.UNRESOLVED, LifecycleLocation.unresolved(),
                LifecycleRevision.INITIAL, null, -10_000,
                ReconciliationGeneration.INITIAL, null
        ));
    }

    private CompanionSnapshot snapshot(SnapshotId id,
                                       String json,
                                       long revision,
                                       long createdAtMs) {
        return new CompanionSnapshot(
                id, PROFILE, KIND, 1, json, Sha256Hash.ofUtf8(json),
                new LifecycleRevision(revision), true, createdAtMs
        );
    }
}
