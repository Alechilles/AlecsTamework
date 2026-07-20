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

class SqliteSchemaV7MigrationTest {
    @TempDir
    Path tempDir;

    @Test
    void createsIncidentQuarantineCircuitAndProbeAuthority() throws Exception {
        SqliteConnectionManager connections = connections("fresh.sqlite");
        SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
        try (Connection connection = connections.openConnection()) {
            migrator.migrate(connection);
            migrator.migrate(connection);

            assertTrue(migrator.isVersionApplied(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V7));
            assertEquals(Set.of(
                    "persistence_incidents",
                    "persistence_incident_scopes",
                    "persistence_quarantines",
                    "persistence_feature_circuits",
                    "persistence_feature_circuit_audit",
                    "persistence_storage_probe"
            ), tables(connection));
            Set<String> indexes = indexes(connection);
            assertTrue(indexes.contains("uq_persistence_quarantine_active_scope"));
            assertTrue(indexes.contains("idx_persistence_incidents_fingerprint_status"));
            assertTrue(indexes.contains("idx_persistence_incident_scopes_key"));
            assertTrue(indexes.contains("idx_persistence_incidents_telemetry_correlation"));
            assertEquals(0L, scalarLong(connection,
                    "SELECT revision FROM persistence_storage_probe WHERE probe_id = 1"));
        }
    }

    @Test
    void activeScopeUniquenessDoesNotPreventRetainedClearedHistory() throws Exception {
        SqliteConnectionManager connections = connections("quarantine.sqlite");
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            insertIncident(connection, "incident-a");
            insertIncident(connection, "incident-b");
            insertQuarantine(connection, "q-a", "incident-a", "ACTIVE");
            assertThrows(SQLException.class,
                    () -> insertQuarantine(connection, "q-b", "incident-b", "VERIFYING"));
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE persistence_quarantines SET state = 'CLEARED' WHERE quarantine_id = 'q-a'");
            }
            insertQuarantine(connection, "q-b", "incident-b", "ACTIVE");
            assertEquals(2L, scalarLong(connection, "SELECT COUNT(*) FROM persistence_quarantines"));
        }
    }

    @Test
    void additiveUpgradeLeavesCanonicalV6PopulationRowsUnchanged() throws Exception {
        SqliteConnectionManager connections = connections("additive.sqlite");
        SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
        try (Connection connection = connections.openConnection()) {
            migrator.migrateThrough(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V6);
            String profileId = UUID.randomUUID().toString();
            try (PreparedStatement profile = connection.prepareStatement("""
                    INSERT INTO npc_profiles (
                        profile_id, current_npc_uuid, owner_uuid, display_name, role_id, state_json,
                        state_hash, last_world_name, created_at_ms, updated_at_ms, last_active_at_ms
                    ) VALUES (?, NULL, 'owner-a', 'Kaitlin', 'tamed_chicken', '{}', 'hash', 'default', 1, 2, 3)
                    """);
                 PreparedStatement population = connection.prepareStatement("""
                    INSERT INTO companion_population_state (
                        profile_id, ownership_world_name, lifecycle_state, physical_world_name,
                        physical_chunk_x, physical_chunk_z, revision, source, created_at_ms, updated_at_ms
                    ) VALUES (?, 'default', 'CAPTURED', NULL, NULL, NULL, 7, 'test', 4, 5)
                    """)) {
                profile.setString(1, profileId);
                profile.executeUpdate();
                population.setString(1, profileId);
                population.executeUpdate();
            }

            migrator.migrateThrough(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V7);

            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT ownership_world_name, lifecycle_state, revision, source, created_at_ms, updated_at_ms
                    FROM companion_population_state WHERE profile_id = ?
                    """)) {
                statement.setString(1, profileId);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("default", result.getString("ownership_world_name"));
                    assertEquals("CAPTURED", result.getString("lifecycle_state"));
                    assertEquals(7L, result.getLong("revision"));
                    assertEquals("test", result.getString("source"));
                    assertEquals(4L, result.getLong("created_at_ms"));
                    assertEquals(5L, result.getLong("updated_at_ms"));
                }
            }
        }
    }

    @Test
    void surroundingTransactionCanRollBackV7() throws Exception {
        SqliteConnectionManager connections = connections("rollback.sqlite");
        SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
        try (Connection connection = connections.openConnection()) {
            migrator.migrateThrough(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V6);
            connection.setAutoCommit(false);
            migrator.migrateThrough(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V7);
            connection.rollback();
            connection.setAutoCommit(true);

            assertFalse(tableExists(connection, "persistence_incidents"));
            assertFalse(migrator.isVersionApplied(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V7));
        }
    }

    private SqliteConnectionManager connections(String file) {
        return new SqliteConnectionManager(tempDir.resolve(file));
    }

    private void insertIncident(Connection connection, String incidentId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO persistence_incidents (
                    incident_id, fingerprint, status, severity, failure_class, disposition,
                    domain, phase, reason_code, boot_id, opened_at_ms, last_seen_at_ms,
                    occurrence_count, evidence_json
                ) VALUES (?, 'fingerprint', 'OPEN', 'WARNING', 'SCOPED_APPLY_AMBIGUITY',
                    'SCOPED_QUARANTINE', 'MANAGED_COOP', 'APPLY', 'test', 'boot', 1, 1, 1, '{}')
                """)) {
            statement.setString(1, incidentId);
            statement.executeUpdate();
        }
    }

    private void insertQuarantine(Connection connection, String quarantineId,
                                  String incidentId, String state) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO persistence_quarantines (
                    quarantine_id, incident_id, scope_type, scope_key, domain, reason_code,
                    state, evidence_hash, generation, created_at_ms, updated_at_ms
                ) VALUES (?, ?, 'PROFILE', 'profile-a', 'MANAGED_COOP', 'test', ?, 'hash', 0, 1, 1)
                """)) {
            statement.setString(1, quarantineId);
            statement.setString(2, incidentId);
            statement.setString(3, state);
            statement.executeUpdate();
        }
    }

    private Set<String> tables(Connection connection) throws Exception {
        return names(connection, "table", "persistence_%");
    }

    private Set<String> indexes(Connection connection) throws Exception {
        return names(connection, "index", "%persistence%");
    }

    private Set<String> names(Connection connection, String type, String pattern) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = ? AND name LIKE ?")) {
            statement.setString(1, type);
            statement.setString(2, pattern);
            try (ResultSet result = statement.executeQuery()) {
                Stream.Builder<String> names = Stream.builder();
                while (result.next()) names.add(result.getString(1));
                return names.build().collect(Collectors.toSet());
            }
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
}
