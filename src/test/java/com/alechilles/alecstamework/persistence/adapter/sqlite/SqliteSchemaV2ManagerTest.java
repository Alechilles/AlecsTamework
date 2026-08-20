package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import com.alechilles.alecstamework.persistence.migration.PublicPersistenceTargetOpener;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests for the routed-read replacement schema migration. */
class SqliteSchemaV2ManagerTest {
    private static final String OPERATION_ID = "operation-v2-migration";

    @TempDir
    Path tempDir;

    @Test
    void upgradesExactV1AndPreservesOperationOutboxAndCheckpointRows()
            throws Exception {
        SqliteConnectionFactory connections = connections("upgrade.sqlite");
        SqliteSchemaV1Manager v1 = new SqliteSchemaV1Manager(
                connections, () -> -5_000
        );
        assertInstanceOf(PersistenceTransactionResult.Committed.class,
                v1.initialize());
        insertRows(connections);

        SqliteSchemaV2Manager v2 = new SqliteSchemaV2Manager(
                connections, () -> -4_000
        );
        var upgrade = v2.initialize();
        assertInstanceOf(PersistenceTransactionResult.Committed.class,
                upgrade, () -> upgrade.toString());

        assertEquals(2, queryLong(connections,
                "SELECT version FROM schema_history"));
        assertEquals(-5_000, queryLong(connections,
                "SELECT applied_at_ms FROM schema_history"));
        assertEquals(v2.schemaHash(), queryString(connections,
                "SELECT schema_hash FROM schema_history"));
        assertEquals(1, queryLong(connections,
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' "
                        + "AND name = 'idx_projection_outbox_type_sequence'"));
        assertEquals(Map.of(
                "operation_id", OPERATION_ID,
                "event_type", "PROFILE_CHANGED",
                "aggregate_id", "profile-v2",
                "payload_json", "{\"revision\":7}"
        ), outboxRow(connections));
        assertEquals(7, queryLong(connections,
                "SELECT acknowledged_sequence FROM projection_checkpoint "
                        + "WHERE consumer_id = 'profile-projection'"));
        assertInstanceOf(PersistenceReadResult.Found.class, v2.verify());

        SqliteSchemaV2Manager reopened = new SqliteSchemaV2Manager(
                connections, () -> -3_000
        );
        assertInstanceOf(PersistenceTransactionResult.Committed.class,
                reopened.initialize());
        assertEquals(1, queryLong(connections,
                "SELECT COUNT(*) FROM schema_history"));
        assertEquals(1, queryLong(connections,
                "SELECT COUNT(*) FROM projection_outbox"));
        assertEquals(7, queryLong(connections,
                "SELECT acknowledged_sequence FROM projection_checkpoint "
                        + "WHERE consumer_id = 'profile-projection'"));
    }

    @Test
    void freshInitializationCreatesExactVersion2() throws Exception {
        SqliteConnectionFactory connections = connections("fresh.sqlite");
        SqliteSchemaV2Manager v2 = new SqliteSchemaV2Manager(
                connections, () -> -5_000
        );

        var fresh = v2.initialize();
        assertInstanceOf(PersistenceTransactionResult.Committed.class,
                fresh, () -> fresh.toString());
        assertEquals(2, queryLong(connections,
                "SELECT version FROM schema_history"));
        assertEquals(-5_000, queryLong(connections,
                "SELECT applied_at_ms FROM schema_history"));
        assertEquals("ok", queryString(connections, "PRAGMA integrity_check"));
        assertInstanceOf(PersistenceReadResult.Found.class, v2.verify());
        assertDoesNotThrow(() -> {
            try (Connection connection = connections.openReadConnection()) {
                SqliteSchemaV1ReadOnlyGateway.verify(connection);
                SqliteSchemaV2ReadOnlyGateway.verify(connection);
            }
        });
    }

    @Test
    void alteredVersion1IsRejectedWithoutChangingItsHistoryRow()
            throws Exception {
        SqliteConnectionFactory connections = connections("altered.sqlite");
        SqliteSchemaV1Manager v1 = new SqliteSchemaV1Manager(
                connections, () -> -5_000
        );
        assertInstanceOf(PersistenceTransactionResult.Committed.class,
                v1.initialize());
        String originalHash = queryString(connections,
                "SELECT schema_hash FROM schema_history");
        try (Connection connection = connections.openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX idx_projection_outbox_aggregate");
            statement.execute("CREATE INDEX idx_projection_outbox_aggregate "
                    + "ON projection_outbox(event_sequence)");
        }

        PersistenceTransactionResult.RolledBack<?> rejected = assertInstanceOf(
                PersistenceTransactionResult.RolledBack.class,
                new SqliteSchemaV2Manager(connections, () -> -4_000).initialize()
        );
        assertEquals(StorageFailureKind.SCHEMA, rejected.failure().kind());
        assertEquals(1, queryLong(connections,
                "SELECT version FROM schema_history"));
        assertEquals(originalHash, queryString(connections,
                "SELECT schema_hash FROM schema_history"));
    }

    @Test
    void schemaHashUsesExactVersion1HashAndMigrationBytes() throws Exception {
        String v1Hash = new SqliteSchemaV1Manager(
                connections("hash.sqlite"), () -> -5_000
        ).schemaHash();
        byte[] migration;
        try (var stream = getClass().getResourceAsStream(
                "/persistence/schema/v2.sql"
        )) {
            migration = stream.readAllBytes();
        }
        byte[] prefix = (v1Hash + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[prefix.length + migration.length];
        System.arraycopy(prefix, 0, input, 0, prefix.length);
        System.arraycopy(migration, 0, input, prefix.length, migration.length);
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(input)
        );

        assertEquals(expected, new SqliteSchemaV2Manager(
                connections("hash.sqlite"), () -> -5_000
        ).schemaHash());
    }

    @Test
    void freshTargetReopenRemainsRecognizableThroughHistoricalGateway()
            throws Exception {
        PublicPersistenceTargetOpener opener =
                new PublicPersistenceTargetOpener(() -> -100);
        var fresh = opener.open(tempDir);
        opener.open(tempDir);
        try (Connection connection = new SqliteConnectionFactory(
                fresh.databasePath()
        ).openReadConnection()) {
            assertDoesNotThrow(() -> SqliteSchemaV1ReadOnlyGateway.verify(
                    connection
            ));
        }
    }

    private SqliteConnectionFactory connections(String fileName) {
        return new SqliteConnectionFactory(tempDir.resolve(fileName));
    }

    private void insertRows(SqliteConnectionFactory connections) throws Exception {
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement operation = connection.prepareStatement("""
                     INSERT INTO operation_envelope(
                         operation_id, idempotency_key, operation_kind,
                         payload_version, payload_json, phase, feature_scope,
                         created_at_ms, updated_at_ms
                     ) VALUES (?, ?, ?, 1, '{}', 'DURABLE', 'profile', -4, -3)
                     """)) {
            operation.setString(1, OPERATION_ID);
            operation.setString(2, "idempotency-v2-migration");
            operation.setString(3, "profile_mutation");
            operation.executeUpdate();
        }
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement outbox = connection.prepareStatement("""
                     INSERT INTO projection_outbox(
                         operation_id, event_type, aggregate_id,
                         aggregate_revision, payload_version, payload_json,
                         created_at_ms
                     ) VALUES (?, 'PROFILE_CHANGED', 'profile-v2', 7, 1,
                         '{"revision":7}', -2)
                     """)) {
            outbox.setString(1, OPERATION_ID);
            outbox.executeUpdate();
        }
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement checkpoint = connection.prepareStatement("""
                     INSERT INTO projection_checkpoint(
                         consumer_id, acknowledged_sequence, updated_at_ms
                     ) VALUES ('profile-projection', 7, -1)
                     """)) {
            checkpoint.executeUpdate();
        }
    }

    private Map<String, String> outboxRow(SqliteConnectionFactory connections)
            throws Exception {
        try (Connection connection = connections.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT operation_id, event_type, aggregate_id, payload_json
                     FROM projection_outbox
                     """)) {
            assertTrue(row.next());
            Map<String, String> result = Map.of(
                    "operation_id", row.getString("operation_id"),
                    "event_type", row.getString("event_type"),
                    "aggregate_id", row.getString("aggregate_id"),
                    "payload_json", row.getString("payload_json")
            );
            assertTrue(!row.next());
            return result;
        }
    }

    private long queryLong(SqliteConnectionFactory connections, String sql)
            throws Exception {
        try (Connection connection = connections.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    private String queryString(SqliteConnectionFactory connections, String sql)
            throws Exception {
        try (Connection connection = connections.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }
}
