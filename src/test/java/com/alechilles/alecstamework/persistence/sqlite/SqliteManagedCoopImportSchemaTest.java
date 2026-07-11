package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression coverage for additive import-journal installation on every v5 database age. */
class SqliteManagedCoopImportSchemaTest {
    @TempDir
    Path tempDir;

    @Test
    void freshMigrationInstallsImportJournalBeforeV5IsRecorded() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(
                tempDir.resolve("fresh.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);

            assertEquals(1, objectCount(connection, "table", "managed_coop_import_sessions"));
            assertEquals(1, objectCount(connection, "table", "managed_coop_import_sources"));
            assertEquals(1, migrationCount(connection, 5));
        }
    }

    @Test
    void repeatedMigrationKeepsOneSchemaAndPreservesRows() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(
                tempDir.resolve("repeated.sqlite"));
        try (Connection connection = connections.openConnection()) {
            SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
            migrator.migrate(connection);
            insertAuthority(connection);
            insertEmptySession(connection);

            migrator.migrate(connection);

            assertEquals(1, rowCount(connection, "managed_coop_import_sessions"));
            assertEquals(1, objectCount(connection, "trigger",
                    "trg_managed_coop_import_session_audit_immutable"));
            assertEquals(1, migrationCount(connection, 5));
        }
    }

    @Test
    void alreadyV5DatabaseReconcilesMissingAdditiveTablesWithoutVersionBump()
            throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(
                tempDir.resolve("existing-v5.sqlite"));
        try (Connection connection = connections.openConnection()) {
            SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
            migrator.migrate(connection);
            execute(connection, "DROP TABLE managed_coop_import_sources");
            execute(connection, "DROP TABLE managed_coop_import_sessions");

            migrator.migrate(connection);

            assertEquals(1, objectCount(connection, "table", "managed_coop_import_sessions"));
            assertEquals(1, objectCount(connection, "table", "managed_coop_import_sources"));
            assertEquals(1, migrationCount(connection, 5));
        }
    }

    private void insertAuthority(Connection connection) throws Exception {
        execute(connection, """
                INSERT INTO managed_coop_authority (
                    authority_id, world_name, coop_id, x, y, z, authority_state, active,
                    import_version, created_at_ms, updated_at_ms
                ) VALUES ('world|1|2|3', 'world', 'coop_chicken', 1, 2, 3,
                          'IMPORTING_TO_TWORK', 1, 0, -10, -10)
                """);
    }

    private void insertEmptySession(Connection connection) throws Exception {
        String hash = "a".repeat(64);
        execute(connection, ("""
                INSERT INTO managed_coop_import_sessions (
                    session_id, authority_id, world_name, coop_id, x, y, z,
                    audit_version, audit_fingerprint, audit_envelope_json, audit_envelope_hash,
                    layout_id, resident_list_class_name, produce_payload, produce_fingerprint,
                    source_count, state, active, begin_command_id, created_at_ms, updated_at_ms
                ) VALUES ('session', 'world|1|2|3', 'world', 'coop_chicken', 1, 2, 3,
                          1, '%s', '{}', '%s', 'layout', 'list',
                          '{}', '%s', 0, 'ACTIVE', 1, '%s', -10, -10)
                """).formatted(hash, hash, hash, hash));
    }

    private int objectCount(Connection connection, String type, String name) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type = '" + type
                             + "' AND name = '" + name + "'")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private int migrationCount(Connection connection, int version) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM schema_migrations WHERE version = " + version)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private int rowCount(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
