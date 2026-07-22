package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies schema-v8 is additive, idempotent, constrained, and transactionally rollback-safe. */
class SqliteSchemaV8MigrationTest {
    @TempDir
    Path tempDir;

    @Test
    void repeatedMigrationCreatesEveryHydragonPersistenceAuthority() throws Exception {
        SqliteConnectionManager connections = connections("fresh.sqlite");
        SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
        try (Connection connection = connections.openConnection()) {
            migrator.migrateThrough(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V8);
            migrator.migrateThrough(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V8);

            assertTrue(migrator.isVersionApplied(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V8));
            assertTrue(tableExists(connection, "api_profile_data_operations"));
            assertTrue(columnExists(connection, "api_profile_data", "revision"));
            assertEquals(Set.of(
                    "capture_attempts",
                    "capture_attempt_tombstones",
                    "capture_failure_cooldowns",
                    "companion_population_group_classifications",
                    "companion_population_group_assignments",
                    "companion_population_group_operations",
                    "companion_population_group_count_evidence",
                    "companion_population_group_event_receipts",
                    "companion_provisioning_operations"
            ), featureTables(connection));
        }
    }

    @Test
    void upgradePreservesV7Population() throws Exception {
        SqliteConnectionManager connections = connections("upgrade.sqlite");
        SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
        try (Connection connection = connections.openConnection()) {
            migrator.migrateThrough(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V7);
            String profileId = insertProfileAndPopulation(connection);

            migrator.migrateThrough(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V8);

            assertEquals(1L, scalarLong(connection,
                    "SELECT COUNT(*) FROM companion_population_state WHERE profile_id = '" + profileId + "'"));
        }
    }

    @Test
    void malformedScopeAndDuplicateProvisioningKeyFailClosed() throws Exception {
        SqliteConnectionManager connections = connections("constraints.sqlite");
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            insertProvisioning(connection, "operation-a");
            assertThrows(SQLException.class, () -> insertProvisioning(connection, "operation-b"));
            assertThrows(SQLException.class, () -> insertInvalidCountEvidence(connection));
        }
    }

    @Test
    void surroundingTransactionRollsBackDdlAndVersionMarker() throws Exception {
        SqliteConnectionManager connections = connections("rollback.sqlite");
        SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
        try (Connection connection = connections.openConnection()) {
            migrator.migrateThrough(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V7);
            connection.setAutoCommit(false);
            migrator.migrateThrough(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V8);
            connection.rollback();
            connection.setAutoCommit(true);

            assertFalse(tableExists(connection, "capture_attempts"));
            assertFalse(migrator.isVersionApplied(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V8));
        }
    }

    private String insertProfileAndPopulation(Connection connection) throws Exception {
        String profileId = UUID.randomUUID().toString();
        try (PreparedStatement profile = connection.prepareStatement("""
                INSERT INTO npc_profiles (
                    profile_id, current_npc_uuid, owner_uuid, role_id, last_world_name,
                    created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, NULL, 'owner-a', 'dragon-role', 'default', 1, 2, 3)
                """);
             PreparedStatement population = connection.prepareStatement("""
                INSERT INTO companion_population_state (
                    profile_id, ownership_world_name, lifecycle_state, physical_world_name,
                    physical_chunk_x, physical_chunk_z, revision, source, created_at_ms, updated_at_ms
                ) VALUES (?, 'default', 'CAPTURED', NULL, NULL, NULL, 4, 'test', 5, 6)
                """)) {
            profile.setString(1, profileId);
            profile.executeUpdate();
            population.setString(1, profileId);
            population.executeUpdate();
        }
        return profileId;
    }

    private void insertProvisioning(Connection connection, String operationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_provisioning_operations (
                    operation_id, caller_namespace, idempotency_key, owner_uuid, target_role_id,
                    requested_disposition, provisional_profile_id, state, created_at_ms, updated_at_ms
                ) VALUES (?, 'hydragon', 'soul-bond-1', 'owner-a', 'miniwyvern',
                    'PROVISIONED_DORMANT', 'profile-provisional', 'PREPARING_DORMANT', 1, 1)
                """)) {
            statement.setString(1, operationId);
            statement.executeUpdate();
        }
    }

    private void insertInvalidCountEvidence(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO companion_population_group_operations (
                        operation_id, profile_id, operation_type, state, expected_population_revision,
                        classification_revision, old_group_ids_json, new_group_ids_json,
                        created_at_ms, updated_at_ms
                    ) VALUES ('groups-a', 'profile-a', 'PROVISION_DORMANT', 'PREPARED', 0,
                        1, '[]', '["soul_bond"]', 1, 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO companion_population_group_count_evidence (
                        operation_id, owner_uuid, group_id, scope_kind, scope_world_name,
                        committed_owned_before, committed_active_before,
                        pending_owned_before, pending_active_before, owned_delta, active_delta,
                        max_owned, max_active, policy_revision, state, created_at_ms, updated_at_ms
                    ) VALUES ('groups-a', 'owner-a', 'soul_bond', 'GLOBAL', 'not-empty',
                        0, 0, 0, 0, 1, 0, 1, 1, 1, 'RESERVED', 1, 1)
                    """);
        }
    }

    private Set<String> featureTables(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT name FROM sqlite_master
                WHERE type = 'table'
                  AND (name LIKE 'capture_%'
                    OR name LIKE 'companion_population_group_%'
                    OR name = 'companion_provisioning_operations')
                """);
             ResultSet result = statement.executeQuery()) {
            Stream.Builder<String> names = Stream.builder();
            while (result.next()) {
                names.add(result.getString(1));
            }
            return names.build().collect(Collectors.toSet());
        }
    }

    private long scalarLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private boolean tableExists(Connection connection, String name) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private SqliteConnectionManager connections(String filename) {
        return new SqliteConnectionManager(tempDir.resolve(filename));
    }
}
