package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedCoopDiagnosticsServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void reportsCleanEmptyV5Runtime() {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            ManagedCoopDiagnosticsService.AuditReport report =
                    runtime.getManagedCoopDiagnosticsService().inspect();

            assertEquals(ManagedCoopDiagnosticsService.ReportStatus.COMPLETE, report.status());
            assertEquals(0L, report.activeAuthorities());
            assertEquals(0L, report.activeResidents());
            assertEquals(0L, report.activeOperations());
            assertTrue(report.compositeIndexTrusted());
            assertFalse(report.requiresAttention());
        }
    }

    @Test
    void surfacesAuthorityStateAndUnresolvedImportConflict() throws Exception {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            SqliteConnectionManager connections = new SqliteConnectionManager(runtime.getSqlitePath());
            try (Connection connection = connections.openConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO managed_coop_authority (
                          authority_id, world_name, coop_id, x, y, z, authority_state,
                          active, import_version, created_at_ms, updated_at_ms
                        ) VALUES ('authority-1', 'default', 'Coop_Chicken', 1, 2, 3,
                          'CONFLICT', 1, 5, 1, 1)
                        """);
                statement.execute("""
                        INSERT INTO coop_import_conflicts (
                          conflict_id, authority_id, world_name, coop_id, x, y, z,
                          resident_slot, conflict_kind, source_fingerprint, source_payload,
                          resolution_state, created_at_ms
                        ) VALUES ('conflict-1', 'authority-1', 'default', 'Coop_Chicken',
                          1, 2, 3, 0, 'AMBIGUOUS_SOURCE', 'source-1', '{}', 'UNRESOLVED', 1)
                        """);
            }

            ManagedCoopDiagnosticsService.AuditReport report =
                    runtime.getManagedCoopDiagnosticsService().inspect();

            assertEquals(1L, report.activeAuthoritiesByState().get("CONFLICT"));
            assertEquals(1L, report.unresolvedImportConflicts());
            assertTrue(report.requiresAttention());
        }
    }

    @Test
    void includesExactResidentAndActiveOperationIdentityFromOneTrustedIndexEpoch()
            throws Exception {
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            ManagedCoopAuthorityKey key = new ManagedCoopAuthorityKey("default", 4, 5, 6);
            UUID sourceUuid = UUID.fromString("00000000-0000-0000-0000-000000000071");
            try (Connection connection = new SqliteConnectionManager(runtime.getSqlitePath())
                    .openConnection()) {
                insertProfile(connection, "profile-71", sourceUuid);
                insertAuthority(connection, key, "coop_chicken");
                insertResident(connection, key, sourceUuid);
                insertCaptureOperation(connection, key, sourceUuid);
            }
            assertTrue(runtime.getManagedCoopServices()
                    .compositeIndexRefreshService().refresh().refreshed());

            ManagedCoopDiagnosticsService.AuditReport report =
                    runtime.getManagedCoopDiagnosticsService().inspect();

            assertEquals(1, report.residentDetails().size());
            ManagedCoopDiagnosticsService.ResidentDetail resident =
                    report.residentDetails().getFirst();
            assertEquals("profile-71", resident.profileId());
            assertEquals(sourceUuid, resident.sourceNpcUuid());
            assertEquals(7, resident.residentSlot());
            assertEquals(0L, resident.generation());
            assertEquals(1, report.operationDetails().size());
            ManagedCoopDiagnosticsService.OperationDetail operation =
                    report.operationDetails().getFirst();
            assertEquals("capture-71", operation.operationId());
            assertEquals("CAPTURE", operation.kind());
            assertEquals("PREPARED", operation.state());
            assertEquals(sourceUuid, operation.sourceNpcUuid());
        }
    }

    @Test
    void readsDetailsFromSqlAndRevokesTrustWhenPublishedIndexCountsLag() throws Exception {
        try (TameworkPersistenceRuntime runtime =
                     TameworkPersistenceRuntime.initialize(tempDir, null)) {
            ManagedCoopAuthorityKey key = new ManagedCoopAuthorityKey("default", 7, 8, 9);
            UUID firstUuid = UUID.fromString("00000000-0000-0000-0000-000000000081");
            UUID secondUuid = UUID.fromString("00000000-0000-0000-0000-000000000082");
            SqliteConnectionManager connections = new SqliteConnectionManager(runtime.getSqlitePath());
            try (Connection connection = connections.openConnection()) {
                insertProfile(connection, "profile-81", firstUuid);
                insertAuthority(connection, key, "coop_chicken");
                insertResident(connection, key, "resident-81", "profile-81", 0, firstUuid);
            }
            assertTrue(runtime.getManagedCoopServices()
                    .compositeIndexRefreshService().refresh().refreshed());

            // Simulate a committed write whose paired runtime-index publication has not happened.
            try (Connection connection = connections.openConnection()) {
                insertProfile(connection, "profile-82", secondUuid);
                insertResident(connection, key, "resident-82", "profile-82", 1, secondUuid);
            }

            ManagedCoopDiagnosticsService.AuditReport report =
                    runtime.getManagedCoopDiagnosticsService().inspect();
            Set<String> profiles = report.residentDetails().stream()
                    .map(ManagedCoopDiagnosticsService.ResidentDetail::profileId)
                    .collect(Collectors.toSet());

            assertEquals(2L, report.activeResidents());
            assertEquals(Set.of("profile-81", "profile-82"), profiles);
            assertFalse(report.compositeIndexTrusted(),
                    "SQL/index count disagreement must revoke the trusted diagnostic claim");
        }
    }

    @Test
    void boundsDetailRowsWhileRetainingExactActiveTotals() throws Exception {
        try (TameworkPersistenceRuntime runtime =
                     TameworkPersistenceRuntime.initialize(tempDir, null)) {
            ManagedCoopAuthorityKey key = new ManagedCoopAuthorityKey("default", 10, 11, 12);
            try (Connection connection = new SqliteConnectionManager(runtime.getSqlitePath())
                    .openConnection()) {
                insertAuthority(connection, key, "coop_chicken");
                for (int index = 0; index < 30; index++) {
                    UUID uuid = new UUID(0L, 1_000L + index);
                    String profileId = "profile-cap-" + index;
                    insertProfile(connection, profileId, uuid);
                    insertResident(connection, key, "resident-cap-" + index,
                            profileId, index, uuid);
                }
            }

            ManagedCoopDiagnosticsService.AuditReport report =
                    runtime.getManagedCoopDiagnosticsService().inspect();

            assertEquals(30L, report.activeResidents());
            assertEquals(25, report.residentDetails().size());
            assertEquals(0, report.residentDetails().getFirst().residentSlot());
            assertEquals(24, report.residentDetails().getLast().residentSlot());
        }
    }

    private void insertProfile(Connection connection, String profileId, UUID currentUuid)
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

    private void insertAuthority(Connection connection,
                                 ManagedCoopAuthorityKey key,
                                 String coopId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_authority (
                  authority_id, world_name, coop_id, x, y, z, authority_state,
                  active, import_version, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, 'TWORK_MANAGED', 1, 5, 1, 1)
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

    private void insertResident(Connection connection,
                                 ManagedCoopAuthorityKey key,
                                 UUID sourceUuid) throws Exception {
        insertResident(connection, key, "resident-71", "profile-71", 7, sourceUuid);
    }

    private void insertResident(Connection connection,
                                ManagedCoopAuthorityKey key,
                                String residentId,
                                String profileId,
                                int residentSlot,
                                UUID sourceUuid) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_residents (
                  resident_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
                  profile_id, role_id, resident_uuid, source_npc_uuid, deployed_npc_uuid,
                  snapshot_json, snapshot_hash, snapshot_version, state, generation, active,
                  captured_at_ms, released_at_ms, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, 'coop_chicken', ?, ?, ?, ?,
                  ?, 'Mob_Chicken', ?, ?, NULL, '{}', ?, 1, 'HOUSED', 0, 1,
                  1, 0, 1, 1)
                """)) {
            statement.setString(1, residentId);
            statement.setString(2, key.authorityId());
            statement.setString(3, key.worldName());
            statement.setInt(4, key.x());
            statement.setInt(5, key.y());
            statement.setInt(6, key.z());
            statement.setInt(7, residentSlot);
            statement.setString(8, profileId);
            statement.setString(9, sourceUuid.toString());
            statement.setString(10, sourceUuid.toString());
            statement.setString(11, "a".repeat(64));
            statement.executeUpdate();
        }
    }

    private void insertCaptureOperation(Connection connection,
                                        ManagedCoopAuthorityKey key,
                                        UUID sourceUuid) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_lifecycle_operations (
                  operation_id, operation_kind, profile_id, authority_id, world_name, coop_id,
                  x, y, z, resident_slot, source_npc_uuid, planned_target_uuid,
                  actual_target_uuid, state, snapshot_hash, expected_generation, retry_count,
                  generation, active, created_at_ms, updated_at_ms, completed_at_ms, last_error
                ) VALUES ('capture-71', 'CAPTURE', 'profile-71', ?, ?, 'coop_chicken',
                  ?, ?, ?, 7, ?, NULL, NULL, 'PREPARED', ?, 0, 0, 0, 1, 1, 1, 0, NULL)
                """)) {
            statement.setString(1, key.authorityId());
            statement.setString(2, key.worldName());
            statement.setInt(3, key.x());
            statement.setInt(4, key.y());
            statement.setInt(5, key.z());
            statement.setString(6, sourceUuid.toString());
            statement.setString(7, "a".repeat(64));
            statement.executeUpdate();
        }
    }
}
