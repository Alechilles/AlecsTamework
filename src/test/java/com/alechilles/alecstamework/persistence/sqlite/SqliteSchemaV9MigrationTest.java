package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteSchemaV9MigrationTest {
    @TempDir Path tempDir;

    @Test
    void createsGenericCommandAuthoritiesAndRemovesUnreleasedVesselTables() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve("v9.sqlite"));
        SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
        try (Connection connection = connections.openConnection()) {
            migrator.migrateThrough(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V8);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE bonded_vessel_bindings (binding_id TEXT PRIMARY KEY)");
                statement.execute("CREATE TABLE bonded_vessel_operations (operation_id TEXT PRIMARY KEY)");
            }

            migrator.migrate(connection);
            migrator.migrate(connection);

            assertTrue(migrator.isVersionApplied(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V9));
            assertTrue(tableExists(connection, "command_family_rosters"));
            assertTrue(tableExists(connection, "command_family_roster_memberships"));
            assertTrue(tableExists(connection, "command_timed_summon_sessions"));
            assertFalse(tableExists(connection, "paid_command_revival_operations"));
            assertFalse(tableExists(connection, "paid_command_revival_costs"));
            assertFalse(tableExists(connection, "paid_command_revival_reservations"));
            assertFalse(tableExists(connection, "paid_command_revival_refund_claims"));
            assertFalse(tableExists(connection, "paid_command_revival_apply_plans"));
            assertTrue(columnExists(connection, "capture_attempts", "source_spend_state"));
            assertTrue(columnExists(
                    connection, "capture_attempts", "source_spend_receipted_at_ms"));
            assertTrue(tableExists(connection, "capture_source_refund_claims"));
            assertFalse(tableExists(connection, "bonded_vessel_bindings"));
            assertFalse(tableExists(connection, "bonded_vessel_operations"));
        }
    }

    private boolean tableExists(Connection connection, String table) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) if (column.equals(result.getString("name"))) return true;
            return false;
        }
    }
}
