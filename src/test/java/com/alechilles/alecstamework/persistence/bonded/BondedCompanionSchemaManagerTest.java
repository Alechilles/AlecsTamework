package com.alechilles.alecstamework.persistence.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract tests for the single final bonded-companion schema. */
class BondedCompanionSchemaManagerTest {
    @TempDir Path tempDir;

    @Test
    void emptyDatabaseGetsFinalSevenTablesAndOneExactHistoryRow()
            throws Exception {
        Path database = tempDir.resolve("fresh.sqlite");
        BondedCompanionSchemaManager manager = manager(database);

        assertTrue(manager.initialize().availability().available());
        assertEquals(1, BondedCompanionSchemaManager.VERSION);
        assertEquals(BondedCompanionSchemaManager.requiredTables(), tables(database));
        try (Connection connection = new SqliteConnectionFactory(database)
                .openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT version, lineage, applied_at_ms, schema_hash
                     FROM bonded_schema_history
                     """)) {
            assertTrue(row.next());
            assertEquals(1, row.getInt("version"));
            assertEquals(BondedCompanionSchemaManager.LINEAGE,
                    row.getString("lineage"));
            assertEquals(-10L, row.getLong("applied_at_ms"));
            assertEquals(manager.schemaHash(), row.getString("schema_hash"));
            assertFalse(row.next());
        }
    }

    @Test
    void legacyNonemptyDatabaseFailsClosedWithoutChangingIt() throws Exception {
        Path database = tempDir.resolve("legacy.sqlite");
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE bonded_schema_history(version INTEGER)");
            statement.execute("INSERT INTO bonded_schema_history VALUES (8)");
        }
        Files.deleteIfExists(database.resolveSibling("legacy.sqlite-wal"));
        Files.deleteIfExists(database.resolveSibling("legacy.sqlite-shm"));
        byte[] before = Files.readAllBytes(database);
        assertFalse(Files.exists(database.resolveSibling("legacy.sqlite-wal")));
        assertFalse(Files.exists(database.resolveSibling("legacy.sqlite-shm")));

        BondedCompanionPersistenceReadiness readiness = manager(database)
                .initialize();

        assertFalse(readiness.availability().available());
        assertEquals("bonded-schema-table-mismatch", readiness.diagnosticCode());
        assertArrayEquals(before, Files.readAllBytes(database));
        assertFalse(Files.exists(database.resolveSibling("legacy.sqlite-wal")));
        assertFalse(Files.exists(database.resolveSibling("legacy.sqlite-shm")));
        try (Connection connection = new SqliteConnectionFactory(database)
                .openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "SELECT version FROM bonded_schema_history")) {
            assertTrue(row.next());
            assertEquals(8, row.getInt(1));
            assertFalse(row.next());
        }
    }

    @Test
    void operationTableAllowsOnlyTerminalCurrentOperations() throws Exception {
        Path database = tempDir.resolve("operation.sqlite");
        assertTrue(manager(database).initialize().availability().available());
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO bonded_companion_operation(
                        caller_namespace, idempotency_key, owner_uuid, roster_id,
                        operation_type, request_hash, operation_state, result_json,
                        created_at_ms, updated_at_ms, expires_at_ms
                    ) VALUES (
                        'test', 'accepted', '00000000-0000-0000-0000-000000000001',
                        'roster', 'CAPTURE', '%s', 'SUCCEEDED', '{}', 1, 1, 2
                    )
                    """.formatted("a".repeat(64)));
            assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO bonded_companion_operation(
                        caller_namespace, idempotency_key, owner_uuid, roster_id,
                        operation_type, request_hash, operation_state, result_json,
                        created_at_ms, updated_at_ms, expires_at_ms
                    ) VALUES (
                        'test', 'summon', '00000000-0000-0000-0000-000000000001',
                        'roster', 'SUMMON', '%s', 'SUCCEEDED', '{}', 1, 1, 2
                    )
                    """.formatted("b".repeat(64))));
            assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO bonded_companion_operation(
                        caller_namespace, idempotency_key, owner_uuid, roster_id,
                        operation_type, request_hash, operation_state, result_json,
                        created_at_ms, updated_at_ms, expires_at_ms
                    ) VALUES (
                        'test', 'pending', '00000000-0000-0000-0000-000000000001',
                        'roster', 'STORE', '%s', 'PENDING', '{}', 1, 1, 2
                    )
                    """.formatted("c".repeat(64))));
        }
    }

    private BondedCompanionSchemaManager manager(Path database) {
        return new BondedCompanionSchemaManager(database, () -> -10L);
    }

    private Set<String> tables(Path database) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT name FROM sqlite_master
                     WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                     """)) {
            java.util.HashSet<String> names = new java.util.HashSet<>();
            while (rows.next()) names.add(rows.getString(1));
            return Set.copyOf(names);
        }
    }
}
