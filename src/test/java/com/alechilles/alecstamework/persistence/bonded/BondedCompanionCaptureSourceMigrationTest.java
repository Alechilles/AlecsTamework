package com.alechilles.alecstamework.persistence.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.CaptureAttemptOutcome;
import com.alechilles.alecstamework.api.CaptureSourceConsumption;
import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteBondedCompanionDatabase;
import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteConnectionFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression coverage for v6 capture completion becoming v7 source authority. */
class BondedCompanionCaptureSourceMigrationTest {
    private static final UUID OWNER = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE = UUID.fromString(
            "20000000-0000-0000-0000-000000000002");
    private static final String ROSTER = "hydragon:companions";
    private static final String PROFILE = "profile-v6-capture";
    private static final byte[] CAPTURE_SNAPSHOT =
            "immutable-capture".getBytes(StandardCharsets.UTF_8);
    @TempDir Path tempDir;

    @Test
    void currentVerifierRejectsCaptureEvidenceMissingExactScalar()
            throws Exception {
        Path path = tempDir.resolve("missing-capture-world-evidence.sqlite");
        BondedCompanionSchemaManager manager = createCapture(path);
        executeUpdate(path, """
                UPDATE bonded_companion_capture_source
                SET capture_evidence_json = json_remove(
                    capture_evidence_json, '$.sourceWorldKey')
                """);

        BondedCompanionPersistenceReadiness readiness = manager.verify();

        assertFalse(readiness.availability().available());
        assertEquals("bonded-capture-source-fence-missing",
                readiness.diagnosticCode());
    }

    @Test
    void currentVerifierRejectsEvidenceMissingRequiredPayloadField()
            throws Exception {
        Path path = tempDir.resolve("missing-capture-item-evidence.sqlite");
        BondedCompanionSchemaManager manager = createCapture(path);
        executeUpdate(path, """
                UPDATE bonded_companion_capture_source
                SET capture_evidence_json = json_remove(
                    capture_evidence_json, '$.sourceItemId')
                """);

        BondedCompanionPersistenceReadiness readiness = manager.verify();

        assertFalse(readiness.availability().available());
        assertEquals("bonded-capture-source-fence-missing",
                readiness.diagnosticCode());
    }

    @Test
    void currentVerifierRejectsSnapshotMissingRequiredPayloadField()
            throws Exception {
        Path path = tempDir.resolve("missing-capture-snapshot-payload.sqlite");
        BondedCompanionSchemaManager manager = createCapture(path);
        executeUpdate(path, """
                UPDATE bonded_companion_capture_source
                SET capture_snapshot_json = json_remove(
                    capture_snapshot_json, '$.snapshotJson')
                """);

        BondedCompanionPersistenceReadiness readiness = manager.verify();

        assertFalse(readiness.availability().available());
        assertEquals("bonded-capture-source-fence-missing",
                readiness.diagnosticCode());
    }

    @Test
    void currentVerifierRejectsOperationWithDifferentRequestHash()
            throws Exception {
        Path path = tempDir.resolve("mismatched-capture-operation.sqlite");
        BondedCompanionSchemaManager manager = createCapture(path);
        executeUpdate(path, """
                UPDATE bonded_companion_operation
                SET request_hash = '%s'
                WHERE operation_type = 'CAPTURE'
                """.formatted("b".repeat(64)));

        BondedCompanionPersistenceReadiness readiness = manager.verify();

        assertFalse(readiness.availability().available());
        assertEquals("bonded-capture-source-fence-missing",
                readiness.diagnosticCode());
    }

    @Test
    void failedV7BackfillRollsBackEverySchemaChange() throws Exception {
        Path path = tempDir.resolve("failed-v7-backfill.sqlite");
        BondedCompanionSchemaManager manager = createCapture(path);
        assertEquals(1, simulateV6(path));
        executeUpdate(path, """
                UPDATE bonded_companion_operation
                SET result_json = json_set(
                    result_json,
                    '$.captureEvidence.sourceNpcUuid',
                    'invalid-source-uuid')
                WHERE operation_type = 'CAPTURE'
                """);

        BondedCompanionPersistenceReadiness failed = manager.initialize();

        assertFalse(failed.availability().available());
        assertEquals(6L, queryLong(path,
                "SELECT MAX(version) FROM bonded_schema_history"));
        assertEquals(0L, queryLong(path, """
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table'
                  AND name = 'bonded_companion_capture_source'
                """));
        assertEquals(1L, queryLong(path, """
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'index'
                  AND name = 'bonded_capture_source_once_idx'
                """));

        executeUpdate(path, """
                UPDATE bonded_companion_operation
                SET result_json = json_set(
                    result_json,
                    '$.captureEvidence.sourceNpcUuid',
                    '%s')
                WHERE operation_type = 'CAPTURE'
                """.formatted(SOURCE));
        assertTrue(manager.initialize().availability().available());
    }

    /** Backfill must copy the operation result, never a later live profile. */
    @Test
    void v6BackfillPreservesImmutableCaptureSnapshotPastOperationPruning()
            throws Exception {
        Path path = tempDir.resolve("v6-capture-source-backfill.sqlite");
        BondedCompanionSchemaManager manager =
                new BondedCompanionSchemaManager(path, () -> 10_000L);
        assertTrue(manager.initialize().availability().available());
        SqliteBondedCompanionDatabase database =
                new SqliteBondedCompanionDatabase(path);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                database.createCapturedProfile(operation(), profile(), cleanup(),
                        4, evidence()).code());
        mutateCurrentProfile(path);

        int removedHistory = simulateV6(path);
        assertEquals(2, removedHistory,
                "current schema must contain v7 and v8 authority");
        assertTrue(manager.initialize().availability().available());

        String capturedPayload = queryString(path, """
                SELECT json_extract(
                    json_extract(capture_snapshot_json, '$.snapshotJson'),
                    '$.payload'
                )
                FROM bonded_companion_capture_source
                WHERE source_npc_uuid = '%s'
                """.formatted(SOURCE));
        String currentPayload = queryString(path, """
                SELECT json_extract(snapshot_json, '$.payload')
                FROM bonded_companion_profile WHERE profile_id = '%s'
                """.formatted(PROFILE));
        assertEquals(Base64.getEncoder().encodeToString(CAPTURE_SNAPSHOT),
                capturedPayload);
        assertNotEquals(currentPayload, capturedPayload);

        assertEquals(1, database.pruneOperations(Long.MAX_VALUE, 16));
        assertEquals(1L, queryLong(path, """
                SELECT COUNT(*) FROM bonded_companion_capture_source
                WHERE source_npc_uuid = '%s' AND source_world_key = 'world'
                """.formatted(SOURCE)));
    }

    private int simulateV6(Path path) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(path)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            int deleted = statement.executeUpdate(
                    "DELETE FROM bonded_schema_history WHERE version IN (7, 8)");
            statement.execute("DROP TABLE bonded_companion_capture_source");
            statement.execute("""
                    CREATE UNIQUE INDEX bonded_capture_source_once_idx
                    ON bonded_companion_operation(
                        json_extract(
                            result_json,
                            '$.captureEvidence.sourceNpcUuid'
                        )
                    )
                    WHERE operation_type = 'CAPTURE'
                      AND operation_state = 'SUCCEEDED'
                      AND json_type(
                          result_json,
                          '$.captureEvidence.sourceNpcUuid'
                      ) = 'text'
                    """);
            return deleted;
        }
    }

    private BondedCompanionSchemaManager createCapture(Path path) {
        BondedCompanionSchemaManager manager =
                new BondedCompanionSchemaManager(path, () -> 10_000L);
        assertTrue(manager.initialize().availability().available());
        SqliteBondedCompanionDatabase database =
                new SqliteBondedCompanionDatabase(path);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED,
                database.createCapturedProfile(operation(), profile(), cleanup(),
                        4, evidence()).code());
        return manager;
    }

    private void executeUpdate(Path path, String sql) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(path)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(sql));
        }
    }

    private void mutateCurrentProfile(Path path) throws Exception {
        String payload = Base64.getEncoder().encodeToString(
                "mutated-live".getBytes(StandardCharsets.UTF_8));
        try (Connection connection = new SqliteConnectionFactory(path)
                .openWriterConnection();
             PreparedStatement update = connection.prepareStatement("""
                     UPDATE bonded_companion_profile
                     SET snapshot_json = ? WHERE profile_id = ?
                     """)) {
            update.setString(1, "{\"encoding\":\"base64\",\"payload\":\""
                    + payload + "\"}");
            update.setString(2, PROFILE);
            assertEquals(1, update.executeUpdate());
        }
    }

    private BondedCompanionOperation operation() {
        return new BondedCompanionOperation(
                "spawner-bonded-capture:v1", "capture-v6", "a".repeat(64),
                OWNER, ROSTER, PROFILE, BondedCompanionOperation.Type.CAPTURE,
                10_000L, 20_000L);
    }

    private BondedCompanionCaptureEvidence evidence() {
        return new BondedCompanionCaptureEvidence(
                UUID.fromString("40000000-0000-0000-0000-000000000004"),
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                OWNER, ROSTER, "hydragon:dragon", SOURCE, PROFILE,
                "Dragon_Fire", "spawner-bonded-capture:v1", "capture-v6",
                "Ancient_Stone", "HydragonCapture", 7L, null, -1L,
                CaptureSourceConsumption.SUCCESS_ONLY,
                CaptureSuccessDisposition.STORE_BONDED_COMPANION,
                CaptureAttemptOutcome.CAPTURED, "guaranteed", "world",
                10_000L);
    }

    private BondedCompanionRecord.Profile profile() {
        return new BondedCompanionRecord.Profile(
                PROFILE, OWNER, ROSTER, "hydragon:dragon", "Dragon_Fire",
                BondedCompanionState.STORED, 0L,
                BondedCompanionPayload.of(CAPTURE_SNAPSHOT), 10_000L, 10_000L,
                Map.of(), null, null, null, null, 0L, 0L, null, null);
    }

    private BondedCompanionRecord.Cleanup cleanup() {
        return new BondedCompanionRecord.Cleanup(
                PROFILE + ":capture-source", OWNER, ROSTER, PROFILE, null,
                BondedCompanionRecord.CleanupTarget.SOURCE, SOURCE, "world",
                "capture", BondedCompanionRecord.CleanupState.PENDING, 0,
                10_000L, 10_000L, 20_000L);
    }

    private long queryLong(Path path, String sql) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(path)
                .openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    private String queryString(Path path, String sql) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(path)
                .openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }
}
