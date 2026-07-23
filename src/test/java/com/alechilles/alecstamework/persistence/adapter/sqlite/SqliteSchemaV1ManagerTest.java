package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests for the fresh, single-version replacement schema lineage. */
class SqliteSchemaV1ManagerTest {
    private static final String PROFILE_A = "20000000-0000-0000-0000-000000000001";
    private static final String PROFILE_B = "20000000-0000-0000-0000-000000000002";

    @TempDir
    Path tempDir;

    private SqliteConnectionFactory connections;
    private SqliteSchemaV1Manager schemas;

    @BeforeEach
    void setUp() {
        connections = new SqliteConnectionFactory(tempDir.resolve("tamework-state.sqlite"));
        schemas = new SqliteSchemaV1Manager(connections, () -> -5_000);
    }

    @Test
    void createsOnlyTheFreshV1TablesAndVerifiesIdempotently() throws Exception {
        assertInstanceOf(PersistenceTransactionResult.Committed.class, schemas.initialize());
        assertEquals(SqliteSchemaV1Manager.requiredTables(), tableNames());
        assertEquals(1, queryLong("SELECT version FROM schema_history"));
        assertEquals(-5_000, queryLong("SELECT applied_at_ms FROM schema_history"));
        assertEquals(schemas.schemaHash(), queryString("SELECT schema_hash FROM schema_history"));
        assertInstanceOf(PersistenceReadResult.Found.class, schemas.verify());

        assertInstanceOf(PersistenceTransactionResult.Committed.class, schemas.initialize());
        assertEquals(1, queryLong("SELECT COUNT(*) FROM schema_history"));
        assertEquals(17, SqliteSchemaV1Manager.requiredTables().size());
    }

    @Test
    void lifecycleConstraintsEnforceTheSingleVocabularyAndAllowSignedTime() throws Exception {
        schemas.initialize();
        insertProfile(PROFILE_A, -10);
        execute("""
                INSERT INTO companion_lifecycle(
                    profile_id, owner_uuid, lifecycle_state, location_kind, location_key,
                    world_key, revision, state_changed_at_ms, last_reconciled_generation
                ) VALUES (
                    '20000000-0000-0000-0000-000000000001', NULL,
                    'UNRESOLVED', 'UNRESOLVED', NULL, NULL, 0, -1000, 0
                )
                """);

        assertEquals(-1_000, queryLong(
                "SELECT state_changed_at_ms FROM companion_lifecycle WHERE profile_id = '" + PROFILE_A + "'"
        ));
        insertProfile(PROFILE_B, 0);
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO companion_lifecycle(
                    profile_id, owner_uuid, lifecycle_state, location_kind, location_key,
                    world_key, revision, state_changed_at_ms, last_reconciled_generation
                ) VALUES (
                    '20000000-0000-0000-0000-000000000002', NULL,
                    'CAPTURED', 'NONE', NULL, NULL, 0, 0, 0
                )
                """));
    }

    @Test
    void canonicalJsonAndMetadataHashesAreDatabaseEnforced() throws Exception {
        schemas.initialize();
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO companion_profile(
                         profile_id, display_name, role_id, metadata_json, metadata_hash,
                         last_known_world_key, created_at_ms, updated_at_ms,
                         last_active_at_ms, metadata_revision
                     ) VALUES (?, NULL, NULL, ?, ?, ?, ?, ?, ?, 0)
                     """)) {
            statement.setString(1, PROFILE_A);
            statement.setString(2, "{\"worldTimeMs\":-3000}");
            statement.setString(3, "a".repeat(64));
            statement.setString(4, "world-a");
            statement.setLong(5, -5_000);
            statement.setLong(6, -4_000);
            statement.setLong(7, -3_000);
            statement.executeUpdate();
        }
        assertEquals("world-a", queryString(
                "SELECT last_known_world_key FROM companion_profile WHERE profile_id = '" + PROFILE_A + "'"
        ));

        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO companion_profile(
                    profile_id, metadata_json, metadata_hash, created_at_ms,
                    updated_at_ms, last_active_at_ms, metadata_revision
                ) VALUES (
                    '20000000-0000-0000-0000-000000000002',
                    'not-json', 'short', 0, 0, 0, 0
                )
                """));
    }

    @Test
    void aliasAndSnapshotUniquenessAreDatabaseEnforced() throws Exception {
        schemas.initialize();
        insertProfile(PROFILE_A, 0);
        execute(aliasInsert("00000000-0000-0000-0000-000000000001", PROFILE_A, "CURRENT", 0, "NULL"));
        assertThrows(SQLException.class, () -> execute(
                aliasInsert("00000000-0000-0000-0000-000000000002", PROFILE_A, "CURRENT", 1, "NULL")
        ));

        execute(snapshotInsert("50000000-0000-0000-0000-000000000001", PROFILE_A));
        assertThrows(SQLException.class, () -> execute(
                snapshotInsert("50000000-0000-0000-0000-000000000002", PROFILE_A)
        ));
    }

    @Test
    void unknownOrTamperedLineageFailsClosedWithoutAddingTables() throws Exception {
        try (Connection connection = connections.openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unrelated_development_table(id INTEGER PRIMARY KEY)");
        }
        PersistenceTransactionResult.RolledBack<?> unknown =
                assertInstanceOf(PersistenceTransactionResult.RolledBack.class, schemas.initialize());
        assertEquals(StorageFailureKind.SCHEMA, unknown.failure().kind());
        assertEquals(Set.of("unrelated_development_table"), tableNames());

        Path tamperedPath = tempDir.resolve("tampered.sqlite");
        connections = new SqliteConnectionFactory(tamperedPath);
        schemas = new SqliteSchemaV1Manager(connections, () -> 1);
        schemas.initialize();
        execute("UPDATE schema_history SET schema_hash = '" + "0".repeat(64) + "'");
        PersistenceReadResult.Failed<?> tampered =
                assertInstanceOf(PersistenceReadResult.Failed.class, schemas.verify());
        assertEquals("replacement_schema_history_mismatch", tampered.failure().code());
    }

    @Test
    void bundledSqlParserPreservesQuotedSemicolonsAndComments() {
        assertEquals(
                java.util.List.of(
                        "CREATE TABLE a(value TEXT)",
                        "-- comment;\nINSERT INTO a(value) VALUES ('one;two')",
                        "/* block; */ SELECT \"semi;colon\""
                ),
                SqlScriptParser.statements("""
                        CREATE TABLE a(value TEXT);
                        -- comment;
                        INSERT INTO a(value) VALUES ('one;two');
                        /* block; */ SELECT "semi;colon";
                        """)
        );
        assertThrows(IllegalArgumentException.class,
                () -> SqlScriptParser.statements("SELECT 'unterminated"));
    }

    private void insertProfile(String profileId, long createdAt) throws Exception {
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO companion_profile(
                         profile_id, display_name, role_id, created_at_ms,
                         updated_at_ms, last_active_at_ms, metadata_revision
                     ) VALUES (?, NULL, NULL, ?, ?, ?, 0)
                     """)) {
            statement.setString(1, profileId);
            statement.setLong(2, createdAt);
            statement.setLong(3, createdAt);
            statement.setLong(4, createdAt);
            statement.executeUpdate();
        }
    }

    private String aliasInsert(String npcUuid,
                               String profileId,
                               String state,
                               long generation,
                               String retiredAt) {
        return """
                INSERT INTO companion_alias(
                    npc_uuid, profile_id, alias_generation, alias_state,
                    lease_operation_id, mapped_at_ms, retired_at_ms
                ) VALUES ('%s', '%s', %d, '%s', NULL, -1000, %s)
                """.formatted(npcUuid, profileId, generation, state, retiredAt);
    }

    private String snapshotInsert(String snapshotId, String profileId) {
        return """
                INSERT INTO companion_snapshot(
                    snapshot_id, profile_id, snapshot_kind, payload_version,
                    payload_json, payload_hash, source_lifecycle_revision,
                    is_current, created_at_ms
                ) VALUES ('%s', '%s', 'capture', 1, '{}', '%s', 0, 1, -1000)
                """.formatted(snapshotId, profileId, "a".repeat(64));
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = connections.openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Set<String> tableNames() throws Exception {
        HashSet<String> names = new HashSet<>();
        try (Connection connection = connections.openReadConnection();
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

    private long queryLong(String sql) throws Exception {
        try (Connection connection = connections.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    private String queryString(String sql) throws Exception {
        try (Connection connection = connections.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }
}
