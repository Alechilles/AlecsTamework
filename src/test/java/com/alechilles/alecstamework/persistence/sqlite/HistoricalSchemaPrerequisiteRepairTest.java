package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalSchemaPrerequisiteRepairTest {
    private static final int PROFILE_COUNT = 77;

    @TempDir
    Path tempDir;

    @Test
    void recordedV4WithMissingPrerequisitesUpgradesWithoutLosingProfiles() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(
                tempDir.resolve("incomplete-recorded-v4.sqlite")
        );
        SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();

        try (Connection connection = connections.openConnection()) {
            createHistoricalFixture(connection);

            migrator.migrate(connection);
            migrator.migrate(connection);

            assertEquals("ok", scalarText(connection, "PRAGMA integrity_check"));
            assertEquals(PROFILE_COUNT, scalarInt(connection, "SELECT COUNT(*) FROM npc_profiles"));
            assertEquals(PROFILE_COUNT, scalarInt(
                    connection, "SELECT COUNT(*) FROM companion_population_state"
            ));
            assertEquals(PROFILE_COUNT, scalarInt(
                    connection,
                    "SELECT COUNT(*) FROM companion_population_state "
                            + "WHERE lifecycle_state = 'UNKNOWN_DORMANT' "
                            + "AND physical_world_name IS NULL"
            ));
            assertEquals("owner-0", scalarText(
                    connection, "SELECT owner_uuid FROM npc_profiles WHERE profile_id = 'profile-0'"
            ));
            assertEquals(0, scalarInt(
                    connection, "SELECT created_at_ms FROM npc_profiles WHERE profile_id = 'profile-0'"
            ));
            assertEquals(1, scalarInt(
                    connection, "SELECT COUNT(*) FROM schema_migrations WHERE version = 2001"
            ));
            assertTrue(hasColumn(connection, "schema_migrations", "applied_at_ms"));
            assertTrue(hasColumn(connection, "npc_profiles", "current_npc_uuid"));
            assertTrue(tableExists(connection, "npc_snapshots"));
            assertTrue(tableExists(connection, "coop_slots"));
            assertTrue(tableExists(connection, "profile_states"));
            assertTrue(tableExists(connection, "api_profile_data"));
            assertTrue(migrator.isVersionApplied(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V7));
        }
    }

    private static void createHistoricalFixture(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations "
                    + "(version INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            statement.execute("INSERT INTO schema_migrations VALUES "
                    + "(2, 'schema_v2'), (3, 'schema_v3'), (4, 'schema_v4'), "
                    + "(2001, 'legacy_import')");
            statement.execute("CREATE TABLE npc_profiles "
                    + "(profile_id TEXT PRIMARY KEY, owner_uuid TEXT)");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO npc_profiles(profile_id, owner_uuid) VALUES (?, ?)"
        )) {
            for (int index = 0; index < PROFILE_COUNT; index++) {
                insert.setString(1, "profile-" + index);
                insert.setString(2, index % 2 == 0 ? "owner-" + index : null);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static boolean hasColumn(
            Connection connection,
            String tableName,
            String columnName
    ) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean tableExists(Connection connection, String tableName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?"
        )) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static int scalarInt(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static String scalarText(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
