package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentState;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TameworkPersistenceRuntimeStaleCoopRepairTest {
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("default", 10, 64, 10);
    private static final String COOP_ID = "coop_chicken";

    @TempDir
    Path tempDir;

    @Test
    void startupRepairsOnlyDurablyDisprovedDeployments() throws Exception {
        initializeSchema();
        Seed mismatch = seed("ACTIVE", true, 0);
        Seed captured = seed("CAPTURED", false, 1);
        Seed current = seed("ACTIVE", false, 2);
        Seed recovering = seed("ACTIVE", true, 3);
        writeScenario(mismatch, captured, current, recovering);

        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            assertRetired(runtime, mismatch);
            assertRetired(runtime, captured);
            assertDeployed(runtime, current);
            assertDeployed(runtime, recovering);
            assertTrue(runtime.getHealthService().isHealthy());
            assertNull(runtime.getManagedCoopServices().residentIndex()
                    .snapshot().residentByProfile(mismatch.profileId()));
        }
        assertEquals(0, activeClaims(mismatch.residentId()));
        assertEquals(0, activeClaims(captured.residentId()));
        assertEquals(1, activeClaims(current.residentId()));
        assertEquals(1, activeClaims(recovering.residentId()));
    }

    @Test
    void startupRepairIsIdempotent() throws Exception {
        initializeSchema();
        Seed stale = seed("ACTIVE", true, 0);
        writeScenario(stale);
        try (TameworkPersistenceRuntime ignored = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            // First startup performs the repair.
        }
        try (TameworkPersistenceRuntime runtime = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            ResidentRecord retired = runtime.getManagedCoopResidentRepository()
                    .loadById(stale.residentId());
            assertEquals(2L, retired.generation());
            assertFalse(retired.active());
        }
    }

    private void initializeSchema() {
        try (TameworkPersistenceRuntime ignored = TameworkPersistenceRuntime.initialize(tempDir, null)) {
            // Schema and migration marker are created before the legacy-state fixture is inserted.
        }
    }

    private void writeScenario(Seed... seeds) throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            insertAuthority(connection);
            for (Seed seed : seeds) {
                insertProfile(connection, seed);
                insertPopulation(connection, seed);
                insertResident(connection, seed);
                insertUuidClaim(connection, seed);
            }
            if (seeds.length == 4) {
                insertActiveOperation(connection, seeds[3]);
            }
            connection.commit();
        }
    }

    private static Seed seed(String lifecycle, boolean rotateUuid, int slot) {
        UUID deployedUuid = UUID.randomUUID();
        UUID currentUuid = rotateUuid ? UUID.randomUUID() : deployedUuid;
        String profileId = UUID.randomUUID().toString();
        return new Seed(profileId, "resident:" + profileId, deployedUuid, currentUuid, lifecycle, slot);
    }

    private static void assertRetired(TameworkPersistenceRuntime runtime, Seed seed) throws Exception {
        ResidentRecord resident = runtime.getManagedCoopResidentRepository().loadById(seed.residentId());
        assertEquals(ResidentState.RETIRED, resident.state());
        assertFalse(resident.active());
        assertEquals(2L, resident.generation());
    }

    private static void assertDeployed(TameworkPersistenceRuntime runtime, Seed seed) throws Exception {
        ResidentRecord resident = runtime.getManagedCoopResidentRepository().loadById(seed.residentId());
        assertEquals(ResidentState.DEPLOYED, resident.state());
        assertTrue(resident.active());
        assertEquals(1L, resident.generation());
    }

    private void insertAuthority(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_authority (
                    authority_id, world_name, coop_id, x, y, z, authority_state, active,
                    import_version, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, 'TWORK_MANAGED', 1, 0, 1, 1)
                """)) {
            statement.setString(1, AUTHORITY.authorityId());
            statement.setString(2, AUTHORITY.worldName());
            statement.setString(3, COOP_ID);
            statement.setInt(4, AUTHORITY.x());
            statement.setInt(5, AUTHORITY.y());
            statement.setInt(6, AUTHORITY.z());
            statement.executeUpdate();
        }
    }

    private static void insertProfile(Connection connection, Seed seed) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO npc_profiles (
                    profile_id, current_npc_uuid, owner_uuid, display_name, role_id,
                    state_json, state_hash, last_world_name,
                    created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, NULL, 'Chicken', 'tamed_chicken', NULL, NULL, 'default', 1, 1, 1)
                """)) {
            statement.setString(1, seed.profileId());
            statement.setString(2, seed.currentUuid().toString());
            statement.executeUpdate();
        }
    }

    private static void insertPopulation(Connection connection, Seed seed) throws Exception {
        boolean physical = "ACTIVE".equals(seed.lifecycle());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_population_state (
                    profile_id, ownership_world_name, lifecycle_state, physical_world_name,
                    physical_chunk_x, physical_chunk_z, revision, source, created_at_ms, updated_at_ms
                ) VALUES (?, NULL, ?, ?, ?, ?, 1, 'test', 1, 1)
                """)) {
            statement.setString(1, seed.profileId());
            statement.setString(2, seed.lifecycle());
            statement.setString(3, physical ? "default" : null);
            if (physical) {
                statement.setInt(4, 0);
                statement.setInt(5, 0);
            } else {
                statement.setObject(4, null);
                statement.setObject(5, null);
            }
            statement.executeUpdate();
        }
    }

    private static void insertResident(Connection connection, Seed seed) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_residents (
                    resident_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
                    profile_id, role_id, resident_uuid, source_npc_uuid, deployed_npc_uuid,
                    snapshot_json, snapshot_hash, snapshot_version, state, generation, active,
                    captured_at_ms, released_at_ms, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'tamed_chicken', ?, ?, ?, '{}', NULL,
                    1, 'DEPLOYED', 1, 1, 1, 1, 1, 1)
                """)) {
            statement.setString(1, seed.residentId());
            statement.setString(2, AUTHORITY.authorityId());
            statement.setString(3, AUTHORITY.worldName());
            statement.setString(4, COOP_ID);
            statement.setInt(5, AUTHORITY.x());
            statement.setInt(6, AUTHORITY.y());
            statement.setInt(7, AUTHORITY.z());
            statement.setInt(8, seed.slot());
            statement.setString(9, seed.profileId());
            statement.setString(10, seed.deployedUuid().toString());
            statement.setString(11, seed.deployedUuid().toString());
            statement.setString(12, seed.deployedUuid().toString());
            statement.executeUpdate();
        }
    }

    private static void insertUuidClaim(Connection connection, Seed seed) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_coop_uuid_claims (
                    npc_uuid, resident_id, claim_kind, active, created_at_ms, updated_at_ms
                ) VALUES (?, ?, 'DEPLOYED', 1, 1, 1)
                """)) {
            statement.setString(1, seed.deployedUuid().toString());
            statement.setString(2, seed.residentId());
            statement.executeUpdate();
        }
    }

    private static void insertActiveOperation(Connection connection, Seed seed) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_lifecycle_operations (
                    operation_id, operation_kind, profile_id, authority_id, world_name, coop_id,
                    x, y, z, resident_slot, source_npc_uuid, state, created_at_ms, updated_at_ms
                ) VALUES (?, 'CAPTURE', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', 1, 1)
                """)) {
            statement.setString(1, "operation:" + seed.profileId());
            statement.setString(2, seed.profileId());
            statement.setString(3, AUTHORITY.authorityId());
            statement.setString(4, AUTHORITY.worldName());
            statement.setString(5, COOP_ID);
            statement.setInt(6, AUTHORITY.x());
            statement.setInt(7, AUTHORITY.y());
            statement.setInt(8, AUTHORITY.z());
            statement.setInt(9, seed.slot());
            statement.setString(10, seed.deployedUuid().toString());
            statement.executeUpdate();
        }
    }

    private int activeClaims(String residentId) throws Exception {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM managed_coop_uuid_claims "
                             + "WHERE resident_id = ? AND active = 1")) {
            statement.setString(1, residentId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : -1;
            }
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve(TameworkPersistenceRuntime.SQLITE_FILENAME)
        );
    }

    private record Seed(String profileId,
                        String residentId,
                        UUID deployedUuid,
                        UUID currentUuid,
                        String lifecycle,
                        int slot) {
    }
}
