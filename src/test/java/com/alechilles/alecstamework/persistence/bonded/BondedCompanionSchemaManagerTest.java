package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.api.BondedCompanionActionContext;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.persistence.TameworkDataPathLayout;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for the independent bonded-companion schema lineage and runtime. */
class BondedCompanionSchemaManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void freshStartupCreatesOnlyBondedTablesWithItsOwnHistory() throws Exception {
        Path database = tempDir.resolve("universe/Tamework/Data/bonded-companions.sqlite");
        BondedCompanionSchemaManager manager = manager(database, -5_000L);

        BondedCompanionPersistenceReadiness first = manager.initialize();
        BondedCompanionPersistenceReadiness second = manager.initialize();

        assertTrue(first.availability().available());
        assertTrue(second.availability().available());
        assertEquals(BondedCompanionSchemaManager.requiredTables(), tables(database));
        assertEquals(6, BondedCompanionSchemaManager.VERSION);
        assertEquals(6L, queryLong(database,
                "SELECT COUNT(*) FROM bonded_schema_history"));
        assertEquals(6L, queryLong(database,
                "SELECT MAX(version) FROM bonded_schema_history"));
        assertEquals(-5_000L, queryLong(database,
                "SELECT applied_at_ms FROM bonded_schema_history WHERE version = 6"));
        assertEquals(manager.schemaHash(), queryString(database,
                "SELECT schema_hash FROM bonded_schema_history WHERE version = 6"));
        assertEquals(1L, queryLong(database, """
                SELECT COUNT(*) FROM pragma_table_info(
                    'bonded_companion_cleanup'
                ) WHERE name = 'world_key'
                """));
    }

    @Test
    void bundledV1RemainsByteStableForUpgradeRecognition() throws Exception {
        assertEquals("e718e63edfb2ebb6c8f19f2ca580eb17e9e12b4c0a4c9f9ba322f62e3d2a26ec",
                hash(resource("/persistence/bonded/v1.sql")));
    }

    @Test
    void bundledV2RemainsByteStableForUpgradeRecognition() throws Exception {
        assertEquals("d21790785972ab126ea9723b17148fe2d99d75ec355becddb090e563fad1fc19",
                hash(resource("/persistence/bonded/v2.sql")));
    }

    @Test
    void bundledV3RemainsByteStableForUpgradeRecognition() throws Exception {
        assertEquals(
                "aec0fd76bd67d52047034a4acedac4bbeab36fab48fda405c50d576f31f328cf",
                hash(resource("/persistence/bonded/v3.sql"))
        );
    }

    @Test
    void bundledV5RemainsByteStableForUpgradeRecognition() throws Exception {
        assertEquals(
                "64ae60b2c91d9939b7826d69a3a9e961181d39eb0b1682f1d3948d399e256d48",
                hash(resource("/persistence/bonded/v5.sql"))
        );
    }

    @Test
    void v4UpgradePinsHistoricalTerminalRevivesForPaymentRepair()
            throws Exception {
        Path database = tempDir.resolve("historical-v4-revive.sqlite");
        BondedCompanionSchemaManager manager = manager(database, -8_000L);
        assertTrue(manager.initialize().availability().available());
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            assertEquals(2, statement.executeUpdate(
                    "DELETE FROM bonded_schema_history WHERE version IN (5, 6)"));
            statement.execute("DROP INDEX bonded_capture_source_once_idx");
            statement.executeUpdate("""
                    ALTER TABLE bonded_companion_operation
                    DROP COLUMN expected_revision
                    """);
            assertEquals(1, statement.executeUpdate("""
                    INSERT INTO bonded_companion_operation(
                        caller_namespace, idempotency_key, owner_uuid, roster_id,
                        profile_id, operation_type, request_hash,
                        operation_state, result_json, created_at_ms,
                        updated_at_ms, expires_at_ms
                    ) VALUES (
                        'legacy-panel', 'revive-v4',
                        '10000000-0000-0000-0000-000000000001', 'roster-a',
                        NULL, 'REVIVE',
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'REJECTED',
                        '{"code":"INVALID_STATE","reason":"legacy",'
                            || '"valueType":"PROFILE","value":null}',
                        -10000, -9000, 10000
                    )
                    """));
        }

        assertTrue(manager(database, -7_000L).initialize()
                .availability().available());

        assertEquals(6L, queryLong(database,
                "SELECT MAX(version) FROM bonded_schema_history"));
        assertEquals(Long.MAX_VALUE, queryLong(database, """
                SELECT expires_at_ms FROM bonded_companion_operation
                WHERE caller_namespace = 'legacy-panel'
                  AND idempotency_key = 'revive-v4'
                """));
        assertEquals(1L, queryLong(database, """
                SELECT COUNT(*) FROM pragma_table_info(
                    'bonded_companion_operation'
                ) WHERE name = 'expected_revision'
                """));

        BondedCompanionStore store =
                new com.alechilles.alecstamework.persistence.adapter.sqlite
                        .SqliteBondedCompanionDatabase(database);
        int recovered = new BondedCompanionPaymentRecoveryService(
                store, () -> -6_000L).recoverAwaitingWithoutEscrow(
                UUID.fromString(
                        "10000000-0000-0000-0000-000000000001"),
                8, absentInventory()).toCompletableFuture().join();

        assertEquals(1, recovered);
        assertTrue(store.listAwaitingProfilePaymentSettlements(
                UUID.fromString(
                        "10000000-0000-0000-0000-000000000001"),
                8).isEmpty());
        assertFalse(queryLong(database, """
                SELECT expires_at_ms FROM bonded_companion_operation
                WHERE caller_namespace = 'legacy-panel'
                  AND idempotency_key = 'revive-v4'
                """) == Long.MAX_VALUE);
    }

    private BondedCompanionActionContext.Inventory absentInventory() {
        return new BondedCompanionActionContext.Inventory() {
            @Override public int availableQuantity(String itemId) { return 0; }

            @Override public CompletionStage<
                    BondedCompanionActionContext.ChargeReceipt>
                    findChargeAsync(String operationId) {
                return CompletableFuture.completedFuture(null);
            }

            @Override public BondedCompanionActionContext.ChargeReceipt
                    consumeExact(String operationId, String itemId,
                                 int quantity) {
                throw new AssertionError("Recovery must not charge");
            }
        };
    }

    @Test
    void historicalV1UpgradesToCurrentAndPreservesTypedReplayAndSnapshot()
            throws Exception {
        Path database = tempDir.resolve("historical-v1.sqlite");
        initializeHistoricalV1(database, -12_000L);
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO bonded_companion_profile(
                        profile_id, owner_uuid, roster_id, family_id, role_id,
                        state, revision, snapshot_json, created_at_ms,
                        updated_at_ms, policy_json, display_name, species, gender,
                        revive_cooldown_until_ms, revive_count
                    ) VALUES (
                        'profile-v1',
                        '10000000-0000-0000-0000-000000000001',
                        'roster-a', 'family:wolf', 'role:companion',
                        'STORED', 0, '{"health":10}', -11000, -11000,
                        '{"policy":"unlimited"}', 'Wolf', 'Wolf', 'Female', 0, 0
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO bonded_companion_extension_data(
                        profile_id, namespace, json_payload, revision, updated_at_ms
                    ) VALUES ('profile-v1', 'example:stats', '{"xp":1}', 0, -11000)
                    """);
            statement.executeUpdate("""
                    INSERT INTO bonded_companion_cleanup(
                        cleanup_id, owner_uuid, roster_id, profile_id,
                        target_kind, target_npc_uuid, cleanup_reason,
                        cleanup_state, attempt_count, next_attempt_at_ms,
                        created_at_ms, retained_until_ms
                    ) VALUES (
                        'cleanup-v1',
                        '10000000-0000-0000-0000-000000000001', 'roster-a',
                        'profile-v1', 'PROJECTION',
                        '20000000-0000-0000-0000-000000000001', 'legacy',
                        'COMPLETED', 1, -10000, -11000, -1
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO bonded_companion_operation(
                        caller_namespace, idempotency_key, owner_uuid, roster_id,
                        profile_id, operation_type, request_hash, operation_state,
                        result_json, created_at_ms, updated_at_ms, expires_at_ms
                    ) VALUES (
                        'legacy', 'operation-v1',
                        '10000000-0000-0000-0000-000000000001', 'roster-a',
                        'profile-v1', 'PROVISION',
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'SUCCEEDED', json_object(
                            'profileId', 'profile-v1',
                            'ownerUuid', '10000000-0000-0000-0000-000000000001',
                            'rosterId', 'roster-a',
                            'familyId', 'family:wolf',
                            'roleId', 'role:companion',
                            'state', 'STORED',
                            'revision', 0,
                            'snapshotJson', '{"health":10}',
                            'createdAtMs', -11000,
                            'updatedAtMs', -11000,
                            'policyJson', '{"policy":"unlimited"}',
                            'displayName', 'Wolf',
                            'species', 'Wolf',
                            'gender', 'Female',
                            'reviveCooldownUntilMs', 0,
                            'reviveCount', 0
                        ), -11000, -11000, 10000
                    )
                    """);
        }

        BondedCompanionSchemaManager manager = manager(database, -10_000L);
        BondedCompanionPersistenceReadiness readiness = manager.initialize();

        assertTrue(readiness.availability().available());
        assertEquals(6L, queryLong(database,
                "SELECT COUNT(*) FROM bonded_schema_history"));
        assertEquals(6L, queryLong(database,
                "SELECT MAX(version) FROM bonded_schema_history"));
        BondedCompanionStore store =
                new com.alechilles.alecstamework.persistence.adapter.sqlite
                        .SqliteBondedCompanionDatabase(database);
        BondedCompanionRecord.Profile migrated = store.findProfile(
                        java.util.UUID.fromString(
                                "10000000-0000-0000-0000-000000000001"),
                        "roster-a", "profile-v1").orElseThrow();
        assertEquals("{\"health\":10}", new String(
                migrated.snapshot().bytes(), StandardCharsets.UTF_8));
        assertEquals("{\"xp\":1}", new String(store.findExtensionData(
                        java.util.UUID.fromString(
                                "10000000-0000-0000-0000-000000000001"),
                        "roster-a", "profile-v1", "example:stats")
                .orElseThrow().payload().bytes(), StandardCharsets.UTF_8));
        assertEquals(1, store.listCleanup(
                java.util.UUID.fromString(
                        "10000000-0000-0000-0000-000000000001"),
                        "roster-a", 10).size());
        assertEquals(
                BondedCompanionRecord.Cleanup.LEGACY_UNKNOWN_WORLD,
                store.listCleanup(
                        java.util.UUID.fromString(
                                "10000000-0000-0000-0000-000000000001"),
                        "roster-a", 10).getFirst().worldKey()
        );
        assertEquals(1L, queryLong(database,
                "SELECT COUNT(*) FROM bonded_companion_operation"));
        BondedCompanionOperation replayOperation = new BondedCompanionOperation(
                "legacy", "operation-v1", "a".repeat(64), migrated.ownerUuid(),
                migrated.rosterId(), migrated.profileId(),
                BondedCompanionOperation.Type.PROVISION, -9_000L, 20_000L);
        BondedCompanionStoreResult<BondedCompanionRecord.Profile> replay =
                store.createProfile(replayOperation, migrated);
        assertEquals(BondedCompanionStoreResult.Code.APPLIED, replay.code());
        assertTrue(replay.replayed());
        assertEquals(migrated, replay.value());
    }

    @Test
    void historicalV2NoValueTerminalOperationUpgradesToTypedReplay()
            throws Exception {
        Path database = tempDir.resolve("historical-v2-no-value.sqlite");
        initializeHistoricalV2(database, -12_000L, -11_000L);
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO bonded_companion_operation(
                        caller_namespace, idempotency_key, owner_uuid, roster_id,
                        profile_id, operation_type, request_hash, operation_state,
                        result_json, created_at_ms, updated_at_ms, expires_at_ms
                    ) VALUES (
                        'legacy-v2', 'no-value-rejection',
                        '10000000-0000-0000-0000-000000000001', 'roster-a',
                        'profile-v2', 'PROVISION',
                        'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                        'REJECTED',
                        '{"code":"CONFLICT","reason":"legacy-v2-rejection",'
                            || '"valueType":"NONE","value":null}',
                        -10000, -10000, 10000
                    )
                    """);
        }

        BondedCompanionPersistenceReadiness readiness =
                manager(database, -9_000L).initialize();

        assertTrue(readiness.availability().available());
        assertEquals("PROFILE", queryString(database, """
                SELECT json_extract(result_json, '$.valueType')
                FROM bonded_companion_operation
                WHERE caller_namespace = 'legacy-v2'
                  AND idempotency_key = 'no-value-rejection'
                """));
        BondedCompanionRecord.Profile profile = new BondedCompanionRecord.Profile(
                "profile-v2",
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                "roster-a", "family:wolf", "role:companion",
                BondedCompanionState.STORED, 0,
                BondedCompanionPayload.of("snapshot"
                        .getBytes(StandardCharsets.UTF_8)),
                -10_000L, -10_000L, Map.of(), null, null, null,
                null, 0L, 0L, null, null);
        BondedCompanionOperation operation = new BondedCompanionOperation(
                "legacy-v2", "no-value-rejection", "b".repeat(64),
                profile.ownerUuid(), profile.rosterId(), profile.profileId(),
                BondedCompanionOperation.Type.PROVISION, -9_000L, 10_000L);
        BondedCompanionStoreResult<BondedCompanionRecord.Profile> replay =
                new com.alechilles.alecstamework.persistence.adapter.sqlite
                        .SqliteBondedCompanionDatabase(database)
                        .createProfile(operation, profile);
        assertEquals(BondedCompanionStoreResult.Code.CONFLICT, replay.code());
        assertNull(replay.value());
        assertEquals("legacy-v2-rejection", replay.reason());
        assertTrue(replay.replayed());
    }

    @Test
    void bondedPathUsesCanonicalUniverseScopedDirectory() {
        Path target = tempDir.resolve("universe/Tamework/Data");
        TameworkDataPathLayout layout = new TameworkDataPathLayout(
                target,
                tempDir.resolve("legacy"),
                Optional.empty()
        );

        assertEquals(
                target.resolve("bonded-companions.sqlite").toAbsolutePath().normalize(),
                BondedCompanionDataPath.resolve(layout)
        );
    }

    @Test
    void openingBondedDatabaseDoesNotCreateOrAlterGenericTables() throws Exception {
        Path generic = tempDir.resolve("tamework-state.sqlite");
        SqliteConnectionFactory genericConnections = new SqliteConnectionFactory(generic);
        assertTrue(new SqliteSchemaV1Manager(genericConnections, () -> -10_000L)
                .initialize() instanceof com.alechilles.alecstamework.persistence.kernel
                .PersistenceTransactionResult.Committed<?>);
        Set<String> before = tables(generic);
        long historyBefore = queryLong(generic, "SELECT COUNT(*) FROM schema_history");

        Path bonded = tempDir.resolve("bonded-companions.sqlite");
        assertTrue(manager(bonded, -9_000L).initialize().availability().available());

        assertEquals(before, tables(generic));
        assertEquals(historyBefore,
                queryLong(generic, "SELECT COUNT(*) FROM schema_history"));
        assertFalse(tables(bonded).stream().anyMatch(before::contains));
    }

    @Test
    void tamperedOrForeignSchemaFailsClosedAndRuntimeCloseIsIdempotent()
            throws Exception {
        Path database = tempDir.resolve("bonded-companions.sqlite");
        BondedCompanionSchemaManager manager = manager(database, -7_000L);
        assertTrue(manager.initialize().availability().available());
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE bonded_schema_history SET schema_hash = '"
                    + "0".repeat(64) + "'");
        }

        BondedCompanionPersistenceReadiness failed = manager.verify();
        assertFalse(failed.availability().available());
        assertEquals("bonded-schema-history-mismatch", failed.diagnosticCode());

        BondedCompanionPersistenceRuntime runtime =
                new BondedCompanionPersistenceRuntime(manager);
        assertFalse(runtime.start().availability().available());
        assertFalse(runtime.start().availability().available());
        runtime.close();
        runtime.close();
        assertEquals("bonded-persistence-closed",
                runtime.readiness().diagnosticCode());
    }

    @Test
    void orphanedActiveProfileFailsBondedReadinessWithoutGenericIncidentRows()
            throws Exception {
        Path database = tempDir.resolve("orphaned.sqlite");
        BondedCompanionSchemaManager manager = manager(database, -7_000L);
        assertTrue(manager.initialize().availability().available());
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO bonded_companion_profile(
                        profile_id, owner_uuid, roster_id, family_id, role_id,
                        state, revision, snapshot_json, created_at_ms,
                        updated_at_ms, policy_json,
                        revive_cooldown_until_ms, revive_count
                    ) VALUES (
                        'profile-a',
                        '10000000-0000-0000-0000-000000000001',
                        'roster-a', 'family:wolf', 'role:companion',
                        'ACTIVE', 1,
                        '{"encoding":"base64","payload":"MTA="}',
                        -10000, -9000,
                        '{}', 0, 0
                    )
                    """);
        }

        BondedCompanionPersistenceReadiness readiness = manager.verify();
        assertFalse(readiness.availability().available());
        assertEquals("bonded-orphaned-active-lease", readiness.diagnosticCode());
        assertFalse(tables(database).contains("persistence_incident"));
        assertFalse(tables(database).contains("projection_outbox"));
    }

    @Test
    void rawSnapshotShapeMismatchFailsClosedWithoutChangingTheRow()
            throws Exception {
        for (String raw : Set.of("{}", "[]", "\"scalar\"", "null")) {
            Path database = tempDir.resolve("raw-"
                    + Integer.toHexString(raw.hashCode()) + ".sqlite");
            BondedCompanionSchemaManager manager = manager(database, -7_000L);
            assertTrue(manager.initialize().availability().available());
            insertRawProfile(database, "profile-raw", raw,
                    "10000000-0000-0000-0000-000000000001", "STORED");

            BondedCompanionPersistenceReadiness readiness = manager.verify();

            assertFalse(readiness.availability().available());
            assertEquals("bonded-stored-record-invalid", readiness.diagnosticCode());
            assertEquals(raw, queryString(database,
                    "SELECT snapshot_json FROM bonded_companion_profile"));
        }
    }

    @Test
    void malformedRawUuidOrStateFailsClosedWithoutRepair()
            throws Exception {
        for (String corruption : Set.of("OWNER", "STATE")) {
            Path database = tempDir.resolve("malformed-" + corruption + ".sqlite");
            BondedCompanionSchemaManager manager = manager(database, -7_000L);
            assertTrue(manager.initialize().availability().available());
            String owner = corruption.equals("OWNER") ? "not-a-uuid"
                    : "10000000-0000-0000-0000-000000000001";
            String state = corruption.equals("STATE") ? "UNKNOWN" : "STORED";
            String snapshot = "{\"encoding\":\"base64\",\"payload\":\"eA==\"}";
            insertRawProfile(database, "profile-raw", snapshot, owner, state);

            BondedCompanionPersistenceReadiness readiness = manager.verify();

            assertFalse(readiness.availability().available());
            assertEquals("bonded-stored-record-invalid", readiness.diagnosticCode());
            assertEquals(1L, queryLong(database,
                    "SELECT COUNT(*) FROM bonded_companion_profile"));
        }
    }

    @Test
    void nestedPolicyObjectFailsClosedBeforeMapperUse() throws Exception {
        Path database = tempDir.resolve("nested-policy.sqlite");
        BondedCompanionSchemaManager manager = manager(database, -7_000L);
        assertTrue(manager.initialize().availability().available());
        String snapshot = "{\"encoding\":\"base64\",\"payload\":\"eA==\"}";
        insertRawProfile(database, "profile-raw", snapshot,
                "10000000-0000-0000-0000-000000000001", "STORED");
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE bonded_companion_profile
                    SET policy_json = '{"nested":{"value":"invalid"}}'
                    WHERE profile_id = 'profile-raw'
                    """);
        }

        BondedCompanionPersistenceReadiness readiness = manager.verify();

        assertFalse(readiness.availability().available());
        assertEquals("bonded-stored-record-invalid", readiness.diagnosticCode());
        assertEquals("{\"nested\":{\"value\":\"invalid\"}}",
                queryString(database, "SELECT policy_json "
                        + "FROM bonded_companion_profile"));
    }

    @Test
    void booleanAndNumberPolicyValuesFailClosedBeforeMapperUse()
            throws Exception {
        for (String policy : Set.of("{\"flag\":true}", "{\"limit\":3}")) {
            Path database = tempDir.resolve("primitive-policy-"
                    + Integer.toHexString(policy.hashCode()) + ".sqlite");
            BondedCompanionSchemaManager manager = manager(database, -7_000L);
            assertTrue(manager.initialize().availability().available());
            String snapshot = "{\"encoding\":\"base64\",\"payload\":\"eA==\"}";
            insertRawProfile(database, "profile-raw", snapshot,
                    "10000000-0000-0000-0000-000000000001", "STORED");
            try (Connection connection = new SqliteConnectionFactory(database)
                    .openWriterConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         UPDATE bonded_companion_profile SET policy_json = ?
                         WHERE profile_id = 'profile-raw'
                         """)) {
                statement.setString(1, policy);
                statement.executeUpdate();
            }

            BondedCompanionPersistenceReadiness readiness = manager.verify();

            assertFalse(readiness.availability().available());
            assertEquals("bonded-stored-record-invalid",
                    readiness.diagnosticCode());
            assertEquals(policy, queryString(database,
                    "SELECT policy_json FROM bonded_companion_profile"));
        }
    }

    @Test
    void malformedTerminalOperationResultFailsClosedWithoutRepair()
            throws Exception {
        Path database = tempDir.resolve("malformed-operation-result.sqlite");
        BondedCompanionSchemaManager manager = manager(database, -7_000L);
        assertTrue(manager.initialize().availability().available());
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO bonded_companion_operation(
                        caller_namespace, idempotency_key, owner_uuid, roster_id,
                        profile_id, operation_type, request_hash, operation_state,
                        result_json, created_at_ms, updated_at_ms, expires_at_ms
                    ) VALUES (
                        'raw-test', 'malformed-result',
                        '10000000-0000-0000-0000-000000000001', 'roster-a',
                        'profile-a', 'PROVISION',
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'SUCCEEDED',
                        '{"code":"APPLIED","reason":null,"valueType":"PROFILE","value":"{}"}',
                        -9000, -9000, 10000
                    )
                    """);
        }

        BondedCompanionPersistenceReadiness readiness = manager.verify();

        assertFalse(readiness.availability().available());
        assertEquals("bonded-stored-record-invalid", readiness.diagnosticCode());
        assertEquals("{\"code\":\"APPLIED\",\"reason\":null,"
                        + "\"valueType\":\"PROFILE\",\"value\":\"{}\"}",
                queryString(database,
                "SELECT result_json FROM bonded_companion_operation"));
    }

    @Test
    void malformedRawExtensionPayloadFailsClosedBeforeMapperUse()
            throws Exception {
        Path database = tempDir.resolve("malformed-extension.sqlite");
        BondedCompanionSchemaManager manager = manager(database, -7_000L);
        assertTrue(manager.initialize().availability().available());
        String snapshot = "{\"encoding\":\"base64\",\"payload\":\"eA==\"}";
        insertRawProfile(database, "profile-raw", snapshot,
                "10000000-0000-0000-0000-000000000001", "STORED");
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("""
                    INSERT INTO bonded_companion_extension_data(
                        profile_id, namespace, json_payload, revision, updated_at_ms
                    ) VALUES ('profile-raw', 'example:bad', '[]', 0, -9000)
                    """);
        }

        BondedCompanionPersistenceReadiness readiness = manager.verify();

        assertFalse(readiness.availability().available());
        assertEquals("bonded-stored-record-invalid", readiness.diagnosticCode());
        assertEquals("[]", queryString(database,
                "SELECT json_payload FROM bonded_companion_extension_data"));
    }

    @Test
    void unknownSchemaHistoryVersionFailsClosedWithoutRepair() throws Exception {
        Path database = tempDir.resolve("unknown-version.sqlite");
        BondedCompanionSchemaManager manager = manager(database, -7_000L);
        assertTrue(manager.initialize().availability().available());
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("""
                    UPDATE bonded_schema_history SET version = 99 WHERE version = 3
                    """);
        }

        BondedCompanionPersistenceReadiness readiness = manager.initialize();

        assertFalse(readiness.availability().available());
        assertEquals("bonded-schema-history-mismatch", readiness.diagnosticCode());
        assertEquals(99L, queryLong(database,
                "SELECT MAX(version) FROM bonded_schema_history"));
    }

    private void insertRawProfile(Path database, String profileId,
                                  String snapshot, String owner, String state)
            throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             Statement pragma = connection.createStatement();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO bonded_companion_profile(
                         profile_id, owner_uuid, roster_id, family_id, role_id,
                         state, revision, snapshot_json, created_at_ms,
                         updated_at_ms, policy_json,
                         revive_cooldown_until_ms, revive_count
                     ) VALUES (?, ?, 'roster-a', 'family:wolf',
                         'role:companion', ?, 0, ?, -10000, -10000,
                         '{}', 0, 0)
                     """)) {
            pragma.execute("PRAGMA ignore_check_constraints=ON");
            insert.setString(1, profileId); insert.setString(2, owner);
            insert.setString(3, state); insert.setString(4, snapshot);
            insert.executeUpdate();
        }
    }

    private BondedCompanionSchemaManager manager(Path database, long now) {
        return new BondedCompanionSchemaManager(
                new SqliteConnectionFactory(database),
                () -> now
        );
    }

    private void initializeHistoricalV1(Path database, long appliedAt)
            throws Exception {
        String script = resource("/persistence/bonded/v1.sql");
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection()) {
            for (String sql : script.split(";\\s*(?:\\R|\\z)")) {
                if (!sql.isBlank()) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO bonded_schema_history(
                        version, lineage, applied_at_ms, schema_hash
                    ) VALUES (1, 'bonded-companions', ?, ?)
                    """)) {
                insert.setLong(1, appliedAt);
                insert.setString(2, hash(script));
                insert.executeUpdate();
            }
        }
    }

    private void initializeHistoricalV2(
            Path database,
            long v1AppliedAt,
            long v2AppliedAt
    ) throws Exception {
        initializeHistoricalV1(database, v1AppliedAt);
        String script = resource("/persistence/bonded/v2.sql");
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection()) {
            for (String sql : script.split(";\\s*(?:\\R|\\z)")) {
                if (!sql.isBlank()) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO bonded_schema_history(
                        version, lineage, applied_at_ms, schema_hash
                    ) VALUES (2, 'bonded-companions', ?, ?)
                    """)) {
                insert.setLong(1, v2AppliedAt);
                insert.setString(2, hash(script));
                insert.executeUpdate();
            }
        }
    }

    private String resource(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("missing " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace('\r', '\n');
        }
    }

    private String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private Set<String> tables(Path database) throws Exception {
        HashSet<String> names = new HashSet<>();
        try (Connection connection = new SqliteConnectionFactory(database)
                .openReadConnection();
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

    private long queryLong(Path database, String sql) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    private String queryString(Path database, String sql) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }
}
