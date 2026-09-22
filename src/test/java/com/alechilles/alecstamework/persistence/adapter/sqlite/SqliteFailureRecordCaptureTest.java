package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceFailureEvidence;
import com.alechilles.alecstamework.persistence.kernel.PersistenceKernelMetrics;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import com.alechilles.alecstamework.persistence.kernel.TransactionReplayPolicy;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationKind;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite failures must preserve useful evidence without altering recovery or exposing save content. */
class SqliteFailureRecordCaptureTest {
    @TempDir Path temporary;
    private final String profile = UUID.randomUUID().toString();
    private final String alias = UUID.randomUUID().toString();
    private final String owner = UUID.randomUUID().toString();
    private final OperationId operation = OperationId.create();

    @Test
    void failedWriteCarriesExpectedAndObservedStateAfterRollbackAndClose() throws Exception {
        var connections = fixture();
        try (SqliteSingleWriter writer = new SqliteSingleWriter(connections,
                new SqliteWriterConfiguration(8, 0, 0, 2_000), (point, id) -> { }, PersistenceKernelMetrics.NO_OP)) {
            IllegalStateException original = new IllegalStateException("operation_prepared_detail_missing");
            var result = writer.submit(new SqliteTransactionCommand<>(operation,
                    new OperationKind("companion_dormant_transition"), TransactionReplayPolicy.NEVER, connection -> {
                execute(connection, "UPDATE companion_lifecycle SET revision = 15 WHERE profile_id = ?", profile);
                throw original;
            })).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            var failed = assertInstanceOf(PersistenceTransactionResult.RolledBack.class, result);
            assertSame(original, failed.failure().cause());
            var evidence = Arrays.stream(original.getSuppressed()).filter(PersistenceFailureEvidence.class::isInstance)
                    .map(PersistenceFailureEvidence.class::cast).findFirst().orElseThrow();
            JsonObject capture = JsonParser.parseString(evidence.json()).getAsJsonObject();
            JsonObject tables = capture.getAsJsonObject("tables");
            JsonObject op = first(tables, "operation_envelope");
            JsonObject lifecycle = first(tables, "companion_lifecycle");
            assertEquals(12, op.get("expected_lifecycle_revision").getAsInt());
            assertEquals(15, lifecycle.get("revision").getAsInt());
            assertEquals("transaction_local_not_commit_evidence", capture.get("view").getAsString());
            assertEquals(lifecycle.get("profile_id"),
                    op.getAsJsonObject("payload_evidence").get("profileId"));
            assertEquals(first(tables, "companion_alias").get("npc_uuid"),
                    op.getAsJsonObject("payload_evidence").getAsJsonObject("source").get("sourceAlias"));
            assertEquals(-7, op.getAsJsonObject("payload_evidence").getAsJsonObject("source")
                    .get("observedGeneration").getAsInt());
            assertEquals(3, first(tables, "profile_extension_data").get("revision").getAsInt());
            String json = evidence.json();
            for (String secret : new String[]{profile, alias, owner, "private-pet", "private-world",
                    "private-inventory", "private-extension", "private-data-key"}) assertFalse(json.contains(secret), secret);
        }
        try (Connection connection = connections.openReadConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT revision FROM companion_lifecycle")) {
            assertTrue(rows.next());
            assertEquals(14, rows.getInt(1));
        }
    }

    @Test
    void missingFeatureTableRetainsEarlierEvidenceAndRestoresConnectionPolicy() throws Exception {
        var connections = fixture();
        try (Connection connection = connections.openWriterConnection()) {
            execute(connection, "DROP TABLE companion_output_claim_item");
            execute(connection, "DROP TABLE companion_output_claim");
            int timeout = timeout(connection);
            JsonObject capture = JsonParser.parseString(SqliteFailureRecordCapture.capture(
                    connection, operation.toString(), StorageFailureKind.UNKNOWN)).getAsJsonObject();
            assertEquals("partial", capture.get("status").getAsString());
            assertEquals(14, first(capture.getAsJsonObject("tables"), "companion_lifecycle").get("revision").getAsInt());
            assertTrue(capture.getAsJsonArray("issues").toString().contains("companion_output_claim"));
            assertEquals(timeout, timeout(connection));
            execute(connection, "UPDATE companion_lifecycle SET revision = 16 WHERE profile_id = ?", profile);
        }
    }

    @Test
    void excessRelatedRowsAreTruncatedAndIndependentCapturesDoNotExposeStableIds() throws Exception {
        var connections = fixture();
        try (Connection connection = connections.openWriterConnection()) {
            for (int i = 1; i < 20; i++) execute(connection,
                    "INSERT INTO companion_alias(npc_uuid,profile_id,alias_generation,alias_state,mapped_at_ms,retired_at_ms) VALUES(?,?,?,'RETIRED',1,2)",
                    UUID.randomUUID().toString(), profile, i);
            JsonObject first = JsonParser.parseString(SqliteFailureRecordCapture.capture(
                    connection, operation.toString(), StorageFailureKind.UNKNOWN)).getAsJsonObject();
            JsonObject second = JsonParser.parseString(SqliteFailureRecordCapture.capture(
                    connection, operation.toString(), StorageFailureKind.UNKNOWN)).getAsJsonObject();
            assertEquals(12, first.getAsJsonObject("tables").getAsJsonArray("companion_alias").size());
            assertTrue(first.get("truncated").getAsBoolean());
            assertNotEquals(first.get("operationId"), second.get("operationId"));
        }
    }

    @Test
    void expensiveSampleStopsAndLeavesConnectionUsable() throws Exception {
        var connections = new SqliteConnectionFactory(temporary.resolve("slow.sqlite"), 25);
        try (Connection connection = connections.openWriterConnection()) {
            execute(connection, """
                    CREATE VIEW operation_envelope AS
                    WITH RECURSIVE slow(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM slow WHERE n < 1000000000)
                    SELECT cast(n AS TEXT) AS operation_id, 'test' AS operation_kind, 'PREPARED' AS phase,
                    1 AS payload_version, 0 AS expected_lifecycle_revision, 0 AS attempt_count,
                    NULL AS lease_owner, 0 AS lease_until_ms, NULL AS failure_kind, NULL AS failure_code,
                    1 AS created_at_ms, n AS updated_at_ms, NULL AS durable_at_ms, NULL AS published_at_ms,
                    NULL AS terminal_at_ms, '{}' AS payload_json FROM slow
                    """);
            JsonObject capture = JsonParser.parseString(SqliteFailureRecordCapture.capture(
                    connection, null, StorageFailureKind.UNKNOWN)).getAsJsonObject();
            assertEquals("partial", capture.get("status").getAsString());
            assertTrue(capture.get("truncated").getAsBoolean());
            assertTrue(capture.getAsJsonArray("issues").toString().contains("time_limit"));
            assertEquals(25, timeout(connection));
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT 1")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    private SqliteConnectionFactory fixture() throws Exception {
        var connections = new SqliteConnectionFactory(temporary.resolve("state.sqlite"), 77);
        assertInstanceOf(PersistenceTransactionResult.Committed.class, new SqliteSchemaV2Manager(connections).initialize());
        try (Connection connection = connections.openWriterConnection()) {
            execute(connection, """
                    INSERT INTO companion_profile(profile_id,display_name,created_at_ms,updated_at_ms,last_active_at_ms,metadata_revision)
                    VALUES(?,'private-pet',1,1,1,3)
                    """, profile);
            execute(connection, """
                    INSERT INTO companion_lifecycle(profile_id,owner_uuid,lifecycle_state,location_kind,location_key,world_key,
                    revision,state_changed_at_ms,last_reconciled_generation) VALUES(?,?,'ACTIVE','LIVE_ENTITY',?,'private-world',14,1,0)
                    """, profile, owner, alias);
            execute(connection, """
                    INSERT INTO companion_alias(npc_uuid,profile_id,alias_generation,alias_state,mapped_at_ms)
                    VALUES(?,?,0,'CURRENT',1)
                    """, alias, profile);
            String payload = "{\"profileId\":\"" + profile + "\",\"expectedLifecycleRevision\":12,"
                    + "\"source\":{\"sourceAlias\":\"" + alias + "\",\"sourceWorldKey\":\"private-world\",\"observedGeneration\":-7},"
                    + "\"snapshot\":{\"payloadJson\":\"private-inventory\"},\"displayName\":\"private-pet\"}";
            execute(connection, """
                    INSERT INTO operation_envelope(operation_id,idempotency_key,operation_kind,payload_version,payload_json,phase,
                    feature_scope,expected_lifecycle_revision,created_at_ms,updated_at_ms)
                    VALUES(?,?,'companion_dormant_transition',1,?,'PREPARED','dormant',12,1,1)
                    """, operation.toString(), operation.toString(), payload);
            execute(connection, "INSERT INTO operation_participant VALUES(?,'OPERATION',?)", operation.toString(), operation.toString());
            execute(connection, "INSERT INTO operation_participant VALUES(?,'PROFILE',?)", operation.toString(), profile);
            execute(connection, """
                    INSERT INTO profile_extension_data(profile_id,namespace,data_key,payload_version,json_payload,payload_hash,revision,created_at_ms,updated_at_ms)
                    VALUES(?,'private-extension','private-data-key',1,'{"private":"private-inventory"}',?,3,1,1)
                    """, profile, "a".repeat(64));
        }
        return connections;
    }

    private static JsonObject first(JsonObject tables, String name) {
        return tables.getAsJsonArray(name).get(0).getAsJsonObject();
    }

    private static int timeout(Connection connection) throws Exception {
        try (var statement = connection.createStatement(); var row = statement.executeQuery("PRAGMA busy_timeout")) {
            assertTrue(row.next());
            return row.getInt(1);
        }
    }

    private static void execute(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            statement.execute();
        }
    }
}
