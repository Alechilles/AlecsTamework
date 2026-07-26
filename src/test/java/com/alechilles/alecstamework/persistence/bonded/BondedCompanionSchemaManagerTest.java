package com.alechilles.alecstamework.persistence.bonded;

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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(2, BondedCompanionSchemaManager.VERSION);
        assertEquals(2L, queryLong(database,
                "SELECT COUNT(*) FROM bonded_schema_history"));
        assertEquals(2L, queryLong(database,
                "SELECT MAX(version) FROM bonded_schema_history"));
        assertEquals(-5_000L, queryLong(database,
                "SELECT applied_at_ms FROM bonded_schema_history WHERE version = 2"));
        assertEquals(manager.schemaHash(), queryString(database,
                "SELECT schema_hash FROM bonded_schema_history WHERE version = 2"));
    }

    @Test
    void bundledV1RemainsByteStableForUpgradeRecognition() throws Exception {
        assertEquals("e718e63edfb2ebb6c8f19f2ca580eb17e9e12b4c0a4c9f9ba322f62e3d2a26ec",
                hash(resource("/persistence/bonded/v1.sql")));
    }

    @Test
    void historicalV1UpgradesToV2AndPreservesOpaqueProfileSnapshot()
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
                        updated_at_ms, policy_json,
                        revive_cooldown_until_ms, revive_count
                    ) VALUES (
                        'profile-v1',
                        '10000000-0000-0000-0000-000000000001',
                        'roster-a', 'family:wolf', 'role:companion',
                        'STORED', 0, '{"health":10}', -11000, -11000,
                        '{"policy":"unlimited"}', 0, 0
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
                        'SUCCEEDED', '{"ok":true}', -11000, -11000, 10000
                    )
                    """);
        }

        BondedCompanionSchemaManager manager = manager(database, -10_000L);
        BondedCompanionPersistenceReadiness readiness = manager.initialize();

        assertTrue(readiness.availability().available());
        assertEquals(2L, queryLong(database,
                "SELECT COUNT(*) FROM bonded_schema_history"));
        assertEquals(2L, queryLong(database,
                "SELECT MAX(version) FROM bonded_schema_history"));
        BondedCompanionStore store =
                new com.alechilles.alecstamework.persistence.adapter.sqlite
                        .SqliteBondedCompanionDatabase(database);
        assertEquals("{\"health\":10}", new String(store.findProfile(
                        java.util.UUID.fromString(
                                "10000000-0000-0000-0000-000000000001"),
                        "roster-a", "profile-v1").orElseThrow()
                .snapshot().bytes(), StandardCharsets.UTF_8));
        assertEquals("{\"xp\":1}", new String(store.findExtensionData(
                        java.util.UUID.fromString(
                                "10000000-0000-0000-0000-000000000001"),
                        "roster-a", "profile-v1", "example:stats")
                .orElseThrow().payload().bytes(), StandardCharsets.UTF_8));
        assertEquals(1, store.listCleanup(
                java.util.UUID.fromString(
                        "10000000-0000-0000-0000-000000000001"),
                "roster-a", 10).size());
        assertEquals(1L, queryLong(database,
                "SELECT COUNT(*) FROM bonded_companion_operation"));
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
                    UPDATE bonded_schema_history SET version = 99 WHERE version = 2
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
