package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.persistence.TameworkDataPathLayout;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashSet;
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
        assertEquals(1L, queryLong(database,
                "SELECT version FROM bonded_schema_history"));
        assertEquals(-5_000L, queryLong(database,
                "SELECT applied_at_ms FROM bonded_schema_history"));
        assertEquals(manager.schemaHash(), queryString(database,
                "SELECT schema_hash FROM bonded_schema_history"));
        assertEquals(1L, queryLong(database,
                "SELECT COUNT(*) FROM bonded_schema_history"));
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
