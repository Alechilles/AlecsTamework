package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behavior tests for fresh v2 targets and the v1-to-v2 migration boundary. */
class SqliteSchemaV2MigrationTest {
    @TempDir
    Path tempDir;

    @Test
    void freshV2TargetHasExactObjectsAndOneV2HistoryRow() throws Exception {
        Path database = tempDir.resolve("fresh.sqlite");
        SqliteSchemaV2Manager manager = manager(database, -100);
        assertInstanceOf(PersistenceTransactionResult.Committed.class,
                manager.initialize());

        assertEquals(SqliteSchemaV2Manager.requiredTables(), tableNames(database));
        assertEquals(SqliteSchemaV2Manager.requiredIndexes(), indexNames(database));
        assertEquals(1, rowCount(database, "schema_history"));
        assertEquals(2, queryLong(database, "SELECT version FROM schema_history"));
        assertEquals(manager.schemaHash(), queryString(
                database, "SELECT schema_hash FROM schema_history"));
        assertInstanceOf(PersistenceReadResult.Found.class, manager.verify());
        assertInstanceOf(PersistenceTransactionResult.Committed.class,
                manager.initialize());
    }

    @Test
    void v1MigrationPreservesRowsAndLeavesAReadableSiblingBackup() throws Exception {
        Path database = tempDir.resolve("upgrade.sqlite");
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        SqliteSchemaV1Manager v1 = new SqliteSchemaV1Manager(
                connections, () -> -200
        );
        v1.initialize();
        insertProfile(connections, "profile-a");

        SqliteSchemaV2Manager v2 = manager(database, -100);
        assertInstanceOf(PersistenceTransactionResult.Committed.class,
                v2.initialize());

        assertEquals(1, rowCount(database, "companion_profile"));
        assertEquals("profile-a", queryString(
                database, "SELECT profile_id FROM companion_profile"));
        assertEquals(2, rowCount(database, "schema_history"));
        assertEquals(1, queryLong(database,
                "SELECT version FROM schema_history ORDER BY rowid LIMIT 1"));
        assertEquals(2, queryLong(database,
                "SELECT version FROM schema_history ORDER BY rowid DESC LIMIT 1"));
        assertInstanceOf(PersistenceReadResult.Found.class, v2.verify());
        assertEquals(1, backupCount(database));
    }

    @Test
    void v1MigrationIsIdempotentOnSecondOpen() throws Exception {
        Path database = tempDir.resolve("idempotent.sqlite");
        SqliteSchemaV2Manager manager = manager(database, -300);
        new SqliteSchemaV1Manager(
                new SqliteConnectionFactory(database), () -> -400
        ).initialize();

        assertInstanceOf(PersistenceTransactionResult.Committed.class,
                manager.initialize());
        assertInstanceOf(PersistenceTransactionResult.Committed.class,
                manager.initialize());
        assertEquals(2, rowCount(database, "schema_history"));
        assertEquals(1, backupCount(database));
    }

    @Test
    void unknownManagedObjectIsRejectedWithoutChangingTheDatabase() throws Exception {
        Path database = tempDir.resolve("unknown.sqlite");
        SqliteSchemaV2Manager manager = manager(database, -500);
        manager.initialize();
        try (Connection connection =
                     new SqliteConnectionFactory(database).openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX idx_unknown_managed ON companion_profile(profile_id)");
        }

        assertInstanceOf(PersistenceTransactionResult.RolledBack.class,
                manager.initialize());
        assertInstanceOf(PersistenceReadResult.Failed.class, manager.verify());
    }

    @Test
    void failedDdlRollsBackToAValidV1Database() throws Exception {
        Path database = tempDir.resolve("rollback.sqlite");
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        SqliteSchemaV1Manager v1 = new SqliteSchemaV1Manager(
                connections, () -> -600
        );
        v1.initialize();
        String invalidScript = """
                CREATE TABLE schema_history_v2(
                    version INTEGER PRIMARY KEY,
                    lineage TEXT NOT NULL,
                    applied_at_ms INTEGER NOT NULL,
                    schema_hash TEXT NOT NULL
                );
                THIS IS NOT VALID SQL;
                """;
        SqliteSchemaV2Migration migration = new SqliteSchemaV2Migration(
                connections, () -> -500, "a".repeat(64), invalidScript
        );

        try {
            migration.migrate();
        } catch (Exception expected) {
            // The behavior under test is the transaction rollback.
        }

        assertInstanceOf(PersistenceReadResult.Found.class,
                v1.verify());
        assertEquals(29, tableNames(database).size());
        assertFalse(tableNames(database).contains("schema_history_v2"));
        assertEquals(1, backupCount(database));
    }

    private SqliteSchemaV2Manager manager(Path path, long time) {
        return new SqliteSchemaV2Manager(
                new SqliteConnectionFactory(path), () -> time
        );
    }

    private void insertProfile(SqliteConnectionFactory connections, String id)
            throws Exception {
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO companion_profile(
                         profile_id, created_at_ms, updated_at_ms,
                         last_active_at_ms, metadata_revision
                     ) VALUES (?, -1, -1, -1, 0)
                     """)) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private Set<String> tableNames(Path database) throws Exception {
        Set<String> names = new HashSet<>();
        try (Connection connection =
                     new SqliteConnectionFactory(database).openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT name FROM sqlite_master
                     WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                     """)) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return Set.copyOf(names);
    }

    private Set<String> indexNames(Path database) throws Exception {
        Set<String> names = new HashSet<>();
        try (Connection connection =
                     new SqliteConnectionFactory(database).openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT name FROM sqlite_master
                     WHERE type = 'index' AND name NOT LIKE 'sqlite_%'
                     """)) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return Set.copyOf(names);
    }

    private int rowCount(Path database, String table) throws Exception {
        return (int) queryLong(database, "SELECT COUNT(*) FROM " + table);
    }

    private long queryLong(Path database, String sql) throws Exception {
        try (Connection connection =
                     new SqliteConnectionFactory(database).openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    private String queryString(Path database, String sql) throws Exception {
        try (Connection connection =
                     new SqliteConnectionFactory(database).openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }

    private int backupCount(Path database) throws Exception {
        Path parent = database.getParent();
        String prefix = database.getFileName() + ".v1-backup.";
        try (var paths = Files.list(parent)) {
            return (int) paths
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .count();
        }
    }
}
