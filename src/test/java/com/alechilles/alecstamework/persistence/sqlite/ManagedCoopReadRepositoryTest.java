package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult.FailureKind.INTEGRITY_VIOLATION;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult.FailureKind.INVALID_INPUT;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult.FailureKind.SQL_ERROR;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult.Status.FAILED;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult.Status.LOADED;
import static com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult.Status.NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for fail-closed snapshots that replace the legacy live coop ledger. */
class ManagedCoopReadRepositoryTest {
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final ManagedCoopAuthorityKey COOP_A =
            new ManagedCoopAuthorityKey("alpha", 10, 20, 30);
    private static final ManagedCoopAuthorityKey COOP_B =
            new ManagedCoopAuthorityKey("beta", 1, 2, 3);

    @TempDir
    Path tempDir;

    private SqliteConnectionManager connections;
    private PersistenceWriteQueue writeQueue;
    private ManagedCoopResidentRepository residents;
    private CoopLifecycleOperationRepository operations;

    @BeforeEach
    void setUp() throws Exception {
        connections = new SqliteConnectionManager(tempDir.resolve("managed-coop-reads.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
        }
        writeQueue = new PersistenceWriteQueue(connections, new PersistenceHealthService(), null);
        residents = new ManagedCoopResidentRepository(connections, writeQueue);
        operations = new CoopLifecycleOperationRepository(connections, writeQueue, residents);
    }

    @AfterEach
    void tearDown() {
        if (writeQueue != null) {
            writeQueue.close();
        }
    }

    @Test
    void activeSnapshotsAreDeterministicAndExactAuthorityReadsFailClosed() throws Exception {
        try (Connection connection = connections.openConnection()) {
            insertAuthority(connection, COOP_B, "coop_beta");
            insertAuthority(connection, COOP_A, "coop_alpha");
            insertProfile(connection, "profile-a0", uuid(1));
            insertProfile(connection, "profile-a2", uuid(2));
            insertProfile(connection, "profile-b1", uuid(3));
            insertProfile(connection, "profile-op-a", uuid(4));
            insertProfile(connection, "profile-op-b", uuid(5));
            insertResident(connection, "resident-b1", COOP_B, "coop_beta", 1,
                    "profile-b1", uuid(3), uuid(3), null, HASH_B);
            insertResident(connection, "resident-a2", COOP_A, "coop_alpha", 2,
                    "profile-a2", uuid(2), uuid(2), null, HASH_A);
            insertResident(connection, "resident-a0", COOP_A, "coop_alpha", 0,
                    "profile-a0", uuid(1), uuid(1), null, HASH_A);
            insertOperation(connection, "operation-b", COOP_B, "coop_beta", 3,
                    "profile-op-b", uuid(15), null, HASH_B);
            insertOperation(connection, "operation-a", COOP_A, "coop_alpha", 1,
                    "profile-op-a", uuid(14), null, HASH_A);
        }

        ManagedCoopReadResult<List<ManagedCoopResidentRepository.AuthorityRecord>> authorityResult =
                residents.loadAllActiveAuthorities();
        ManagedCoopReadResult<List<ManagedCoopResidentRepository.ResidentRecord>> residentResult =
                residents.loadAllActiveResidents();
        ManagedCoopReadResult<List<CoopLifecycleOperationRepository.OperationRecord>> operationResult =
                operations.loadAllActiveOperations();

        assertEquals(LOADED, authorityResult.status());
        assertEquals(List.of(COOP_A, COOP_B), authorityResult.value().stream()
                .map(ManagedCoopResidentRepository.AuthorityRecord::authorityKey).toList());
        assertEquals(LOADED, residentResult.status());
        assertEquals(List.of("resident-a0", "resident-a2", "resident-b1"), residentResult.value().stream()
                .map(ManagedCoopResidentRepository.ResidentRecord::residentId).toList());
        assertEquals(LOADED, operationResult.status());
        assertEquals(List.of("operation-a", "operation-b"), operationResult.value().stream()
                .map(CoopLifecycleOperationRepository.OperationRecord::operationId).toList());
        assertThrows(UnsupportedOperationException.class, () -> authorityResult.value().clear());
        assertThrows(UnsupportedOperationException.class, () -> residentResult.value().clear());
        assertThrows(UnsupportedOperationException.class, () -> operationResult.value().clear());

        assertEquals(LOADED, residents.loadAuthority(COOP_A, "COOP_ALPHA").status());
        assertEquals(List.of(0, 2), residents.loadActiveResidents(COOP_A, "coop_alpha").value().stream()
                .map(ManagedCoopResidentRepository.ResidentRecord::residentSlot).toList());
        assertEquals(List.of("operation-a"), operations.loadActiveOperations(COOP_A, "coop_alpha")
                .value().stream().map(CoopLifecycleOperationRepository.OperationRecord::operationId).toList());

        ManagedCoopReadResult<ManagedCoopResidentRepository.AuthorityRecord> conflict =
                residents.loadAuthority(COOP_A, "different_coop");
        assertIntegrityFailure(conflict);
    }

    @Test
    void exactScopedReadsDistinguishMissingAuthorityAndInvalidInput() {
        assertEquals(NOT_FOUND, residents.loadAuthority(COOP_A, "coop_alpha").status());
        assertEquals(NOT_FOUND, residents.loadActiveResidents(COOP_A, "coop_alpha").status());
        assertEquals(NOT_FOUND, operations.loadActiveOperations(COOP_A, "coop_alpha").status());

        ManagedCoopReadResult<ManagedCoopResidentRepository.AuthorityRecord> invalid =
                residents.loadAuthority(COOP_A, " ");
        assertEquals(FAILED, invalid.status());
        assertNotNull(invalid.failure());
        assertEquals(INVALID_INPUT, invalid.failure().kind());
    }

    @Test
    void residentUuidCollisionAcrossAliasColumnsReturnsIntegrityFailure() throws Exception {
        try (Connection connection = connections.openConnection()) {
            insertAuthority(connection, COOP_A, "coop_alpha");
            insertProfile(connection, "profile-a", uuid(1));
            insertProfile(connection, "profile-b", uuid(2));
            insertResident(connection, "resident-a", COOP_A, "coop_alpha", 0,
                    "profile-a", uuid(1), uuid(1), null, HASH_A);
            insertResident(connection, "resident-b", COOP_A, "coop_alpha", 1,
                    "profile-b", uuid(2), uuid(1), null, HASH_B);
        }

        assertIntegrityFailure(residents.loadAllActiveResidents());
    }

    @Test
    void duplicateActiveResidentProfileOrSlotReturnsIntegrityFailure() throws Exception {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            insertAuthority(connection, COOP_A, "coop_alpha");
            insertProfile(connection, "profile-a", uuid(1));
            insertProfile(connection, "profile-b", uuid(2));
            statement.execute("DROP INDEX uq_managed_resident_active_profile");
            statement.execute("DROP INDEX uq_managed_resident_active_slot");
            insertResident(connection, "resident-a", COOP_A, "coop_alpha", 0,
                    "profile-a", uuid(1), uuid(1), null, HASH_A);
            insertResident(connection, "resident-b", COOP_A, "coop_alpha", 0,
                    "profile-b", uuid(2), uuid(2), null, HASH_B);
        }
        ManagedCoopReadResult<List<ManagedCoopResidentRepository.ResidentRecord>> slotConflict =
                residents.loadAllActiveResidents();
        assertIntegrityFailure(slotConflict);
        assertTrue(slotConflict.failure().detail().contains("resident_slot"));

        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM managed_coop_residents");
            insertResident(connection, "resident-a", COOP_A, "coop_alpha", 0,
                    "profile-a", uuid(1), uuid(1), null, HASH_A);
            insertResident(connection, "resident-b", COOP_A, "coop_alpha", 1,
                    "profile-a", uuid(2), uuid(2), null, HASH_B);
        }
        ManagedCoopReadResult<List<ManagedCoopResidentRepository.ResidentRecord>> profileConflict =
                residents.loadAllActiveResidents();
        assertIntegrityFailure(profileConflict);
        assertTrue(profileConflict.failure().detail().contains("resident_profile"));
    }

    @Test
    void malformedResidentHashReturnsIntegrityFailureRatherThanEmptySnapshot() throws Exception {
        try (Connection connection = connections.openConnection()) {
            insertAuthority(connection, COOP_A, "coop_alpha");
            insertProfile(connection, "profile-a", uuid(1));
            insertResident(connection, "resident-a", COOP_A, "coop_alpha", 0,
                    "profile-a", uuid(1), uuid(1), null, "not-a-sha256");
        }

        assertIntegrityFailure(residents.loadAllActiveResidents());
    }

    @Test
    void malformedLifecycleEnumUuidHashAndGenerationAreTypedIntegrityFailures() throws Exception {
        try (Connection connection = connections.openConnection()) {
            insertAuthority(connection, COOP_A, "coop_alpha");
            insertProfile(connection, "profile-a", uuid(1));
            insertOperation(connection, "operation-a", COOP_A, "coop_alpha", 0,
                    "profile-a", uuid(1), uuid(11), HASH_A);
        }

        assertCorruptOperation("operation_kind = 'BROKEN'", "operation_kind = 'CAPTURE'");
        assertCorruptOperation("planned_target_uuid = 'broken'",
                "planned_target_uuid = '" + uuid(11) + "'");
        assertCorruptOperation("snapshot_hash = 'broken'", "snapshot_hash = '" + HASH_A + "'");
        assertCorruptOperation("generation = -1", "generation = 0");
    }

    @Test
    void duplicateActiveLifecycleSlotAndUuidReturnIntegrityFailures() throws Exception {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            insertAuthority(connection, COOP_A, "coop_alpha");
            insertProfile(connection, "profile-a", uuid(1));
            insertProfile(connection, "profile-b", uuid(2));
            statement.execute("DROP INDEX uq_coop_lifecycle_active_slot");
            statement.execute("DROP INDEX uq_coop_lifecycle_active_profile");
            insertOperation(connection, "operation-a", COOP_A, "coop_alpha", 0,
                    "profile-a", uuid(1), null, HASH_A);
            insertOperation(connection, "operation-b", COOP_A, "coop_alpha", 0,
                    "profile-b", uuid(2), null, HASH_B);
        }
        ManagedCoopReadResult<List<CoopLifecycleOperationRepository.OperationRecord>> slotConflict =
                operations.loadAllActiveOperations();
        assertIntegrityFailure(slotConflict);
        assertTrue(slotConflict.failure().detail().contains("lifecycle_slot"));

        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM coop_lifecycle_operations");
            insertOperation(connection, "operation-a", COOP_A, "coop_alpha", 0,
                    "profile-a", uuid(1), null, HASH_A);
            insertOperation(connection, "operation-b", COOP_A, "coop_alpha", 1,
                    "profile-a", uuid(2), null, HASH_B);
        }
        ManagedCoopReadResult<List<CoopLifecycleOperationRepository.OperationRecord>> profileConflict =
                operations.loadAllActiveOperations();
        assertIntegrityFailure(profileConflict);
        assertTrue(profileConflict.failure().detail().contains("lifecycle_profile"));

        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM coop_lifecycle_operations");
            insertOperation(connection, "operation-a", COOP_A, "coop_alpha", 0,
                    "profile-a", uuid(1), null, HASH_A);
            insertOperation(connection, "operation-b", COOP_A, "coop_alpha", 1,
                    "profile-b", uuid(2), uuid(1), HASH_B);
        }
        assertIntegrityFailure(operations.loadAllActiveOperations());
    }

    @Test
    void sqlFailuresAreVisibleAndNeverReportedAsLoadedEmptyState() throws Exception {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE managed_coop_residents");
        }
        ManagedCoopReadResult<List<ManagedCoopResidentRepository.ResidentRecord>> result =
                residents.loadAllActiveResidents();

        assertEquals(FAILED, result.status());
        assertNotNull(result.failure());
        assertEquals(SQL_ERROR, result.failure().kind());
    }

    private void assertCorruptOperation(String corruption, String repair) throws Exception {
        executeIgnoringChecks("UPDATE coop_lifecycle_operations SET " + corruption
                + " WHERE operation_id = 'operation-a'");
        assertIntegrityFailure(operations.loadAllActiveOperations());
        executeIgnoringChecks("UPDATE coop_lifecycle_operations SET " + repair
                + " WHERE operation_id = 'operation-a'");
    }

    private void executeIgnoringChecks(String sql) throws Exception {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA ignore_check_constraints = ON");
            statement.executeUpdate(sql);
        }
    }

    private static void assertIntegrityFailure(ManagedCoopReadResult<?> result) {
        assertEquals(FAILED, result.status());
        assertNotNull(result.failure());
        assertEquals(INTEGRITY_VIOLATION, result.failure().kind());
    }

    private static void insertAuthority(Connection connection,
                                        ManagedCoopAuthorityKey key,
                                        String coopId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_authority (
                    authority_id, world_name, coop_id, x, y, z, authority_state,
                    active, import_version, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, 'TWORK_MANAGED', 1, 0, 1, 1)
                """)) {
            statement.setString(1, key.authorityId());
            statement.setString(2, key.worldName());
            statement.setString(3, coopId);
            statement.setInt(4, key.x());
            statement.setInt(5, key.y());
            statement.setInt(6, key.z());
            statement.executeUpdate();
        }
    }

    private static void insertProfile(Connection connection, String profileId, UUID currentUuid)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO npc_profiles (
                    profile_id, current_npc_uuid, role_id,
                    created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, 'Mob_Chicken', 1, 1, 1)
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, currentUuid.toString());
            statement.executeUpdate();
        }
    }

    private static void insertResident(Connection connection,
                                       String residentId,
                                       ManagedCoopAuthorityKey key,
                                       String coopId,
                                       int slot,
                                       String profileId,
                                       UUID residentUuid,
                                       UUID sourceUuid,
                                       UUID deployedUuid,
                                       String hash) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_residents (
                    resident_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
                    profile_id, role_id, resident_uuid, source_npc_uuid, deployed_npc_uuid,
                    snapshot_json, snapshot_hash, snapshot_version, state, generation, active,
                    captured_at_ms, released_at_ms, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'Mob_Chicken', ?, ?, ?, '{}', ?, 1,
                          'HOUSED', 0, 1, 1, 0, 1, 1)
                """)) {
            int index = 1;
            statement.setString(index++, residentId);
            statement.setString(index++, key.authorityId());
            statement.setString(index++, key.worldName());
            statement.setString(index++, coopId);
            statement.setInt(index++, key.x());
            statement.setInt(index++, key.y());
            statement.setInt(index++, key.z());
            statement.setInt(index++, slot);
            statement.setString(index++, profileId);
            statement.setString(index++, residentUuid.toString());
            statement.setString(index++, sourceUuid == null ? null : sourceUuid.toString());
            statement.setString(index++, deployedUuid == null ? null : deployedUuid.toString());
            statement.setString(index, hash);
            statement.executeUpdate();
        }
    }

    private static void insertOperation(Connection connection,
                                        String operationId,
                                        ManagedCoopAuthorityKey key,
                                        String coopId,
                                        int slot,
                                        String profileId,
                                        UUID sourceUuid,
                                        UUID plannedUuid,
                                        String hash) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_lifecycle_operations (
                    operation_id, operation_kind, profile_id, authority_id, world_name, coop_id,
                    x, y, z, resident_slot, source_npc_uuid, planned_target_uuid,
                    actual_target_uuid, state, snapshot_hash, expected_generation, retry_count,
                    generation, active, created_at_ms, updated_at_ms, completed_at_ms, last_error
                ) VALUES (?, 'CAPTURE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'PREPARED', ?, 0, 0,
                          0, 1, 1, 1, 0, NULL)
                """)) {
            int index = 1;
            statement.setString(index++, operationId);
            statement.setString(index++, profileId);
            statement.setString(index++, key.authorityId());
            statement.setString(index++, key.worldName());
            statement.setString(index++, coopId);
            statement.setInt(index++, key.x());
            statement.setInt(index++, key.y());
            statement.setInt(index++, key.z());
            statement.setInt(index++, slot);
            statement.setString(index++, sourceUuid == null ? null : sourceUuid.toString());
            statement.setString(index++, plannedUuid == null ? null : plannedUuid.toString());
            statement.setString(index, hash);
            statement.executeUpdate();
        }
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", suffix));
    }
}
