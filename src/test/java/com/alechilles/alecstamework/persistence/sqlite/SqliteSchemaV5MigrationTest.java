package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteSchemaV5MigrationTest {
    @TempDir
    Path tempDir;

    @Test
    void classifiesDuplicateCoopRowsAndCrossProfileRecoveryAsConflicts() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve("v4.sqlite"));
        UUID originalUuid = UUID.randomUUID();
        UUID replacementUuid = UUID.randomUUID();
        UUID housedUuid = UUID.randomUUID();
        UUID duplicateProfileUuid = UUID.randomUUID();
        try (Connection connection = connections.openConnection()) {
            createV4Fixture(connection);
            insertProfile(connection, "profile-a", originalUuid, "Mob_Chicken");
            insertProfile(connection, "profile-b", replacementUuid, "Mob_Turkey");
            execute(connection, "INSERT INTO npc_uuid_aliases VALUES ('" + replacementUuid + "', 'profile-b', 1, 1)");
            execute(connection, "INSERT INTO npc_snapshots (profile_id, snapshot_type, payload_json, is_active, created_at_ms) VALUES "
                    + "('profile-a', 'lost', '{\"replacementNpcUuid\":\"" + replacementUuid + "\"}', 1, 10)");
            insertCoopRow(connection, 0, "profile-a", housedUuid, null);
            insertCoopRow(connection, 1, "profile-a", duplicateProfileUuid, null);
            insertCoopRow(connection, 2, "profile-b", null, housedUuid);

            connection.setAutoCommit(false);
            new SqliteSchemaMigrator().migrateThrough(
                    connection, SqliteSchemaMigrator.SCHEMA_VERSION_V5
            );
            connection.commit();

            assertEquals(1, count(connection, "managed_coop_residents"));
            assertEquals(2, count(connection, "coop_import_conflicts"));
            assertEquals(1, count(connection, "managed_coop_uuid_claims"));
            assertEquals("CONFLICT", scalar(connection,
                    "SELECT state FROM npc_recovery_operations WHERE profile_id = 'profile-a'"));
            assertEquals("legacy_replacement_maps_to_different_profile", scalar(connection,
                    "SELECT last_error FROM npc_recovery_operations WHERE profile_id = 'profile-a'"));
            assertEquals("0", scalar(connection,
                    "SELECT generation FROM npc_recovery_operations WHERE profile_id = 'profile-a'"));

            new SqliteSchemaMigrator().migrateThrough(
                    connection, SqliteSchemaMigrator.SCHEMA_VERSION_V5
            );
            assertEquals(1, count(connection, "managed_coop_residents"));
            assertEquals(2, count(connection, "coop_import_conflicts"));
            assertEquals(1, count(connection, "npc_recovery_operations"));
        }
    }

    @Test
    void activeConstraintsAndConflictEvidenceAreEnforced() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve("constraints.sqlite"));
        try (Connection connection = connections.openConnection()) {
            createV4Fixture(connection);
            insertProfile(connection, "profile-a", UUID.randomUUID(), "Mob_Chicken");
            insertCoopRow(connection, 0, "profile-a", UUID.randomUUID(), null);
            new SqliteSchemaMigrator().migrateThrough(
                    connection, SqliteSchemaMigrator.SCHEMA_VERSION_V5
            );

            String authorityId = "world|1|2|3";
            assertThrows(SQLException.class, () -> execute(connection, """
                    INSERT INTO managed_coop_residents (
                        resident_id, authority_id, world_name, coop_id, x, y, z, resident_slot,
                        profile_id, resident_uuid, state, active, created_at_ms, updated_at_ms
                    ) VALUES (
                        'duplicate', 'world|1|2|3', 'world', 'Coop_Chicken', 1, 2, 3, 1,
                        'profile-a', 'duplicate-uuid', 'HOUSED', 1, 1, 1
                    )
                    """));
            execute(connection, """
                    INSERT INTO coop_import_conflicts (
                        conflict_id, authority_id, world_name, coop_id, x, y, z,
                        conflict_kind, source_fingerprint, source_payload, created_at_ms
                    ) VALUES ('manual-conflict', 'world|1|2|3', 'world', 'Coop_Chicken', 1, 2, 3,
                              'TEST', 'fingerprint', 'immutable', 1)
                    """);
            assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE coop_import_conflicts SET source_payload = 'changed' WHERE conflict_id = 'manual-conflict'"));
            assertTrue(authorityId.equals(scalar(connection,
                    "SELECT authority_id FROM managed_coop_authority LIMIT 1")));
        }
    }

    /** Regression: old deployed coop rows have a null source UUID but still need a UUID claim. */
    @Test
    void reappliedV5BackfillsMissingClaimsForLegacyDeployedResidents() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(
                tempDir.resolve("deployed-claim-backfill.sqlite"));
        UUID deployedUuid = UUID.randomUUID();
        try (Connection connection = connections.openConnection()) {
            createV4Fixture(connection);
            insertProfile(connection, "profile-a", deployedUuid, "Mob_Chicken");
            insertCoopRow(connection, 0, "profile-a", null, deployedUuid);

            SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
            migrator.migrateThrough(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V5);
            assertEquals("DEPLOYED", scalar(connection,
                    "SELECT state FROM managed_coop_residents WHERE active = 1"));
            assertEquals("DEPLOYED", scalar(connection,
                    "SELECT claim_kind FROM managed_coop_uuid_claims WHERE active = 1"));

            execute(connection, "DELETE FROM managed_coop_uuid_claims");
            migrator.migrateThrough(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V5);

            assertEquals(1, count(connection, "managed_coop_uuid_claims"));
            assertEquals(deployedUuid.toString(), scalar(connection,
                    "SELECT npc_uuid FROM managed_coop_uuid_claims WHERE active = 1"));
            assertEquals("DEPLOYED", scalar(connection,
                    "SELECT claim_kind FROM managed_coop_uuid_claims WHERE active = 1"));
        }
    }

    @Test
    void migrationFailureRollsBackAndLeavesV4DataUsable() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve("rollback.sqlite"));
        try (Connection connection = connections.openConnection()) {
            createV4Fixture(connection);
            insertProfile(connection, "profile-a", UUID.randomUUID(), "Mob_Chicken");
            insertCoopRow(connection, 0, "profile-a", UUID.randomUUID(), null);
            execute(connection, "CREATE TABLE npc_recovery_operations (operation_id TEXT PRIMARY KEY)");

            connection.setAutoCommit(false);
            assertThrows(Exception.class, () -> new SqliteSchemaMigrator().migrateThrough(
                    connection, SqliteSchemaMigrator.SCHEMA_VERSION_V5
            ));
            connection.rollback();
            connection.setAutoCommit(true);

            assertEquals(1, count(connection, "coop_slots"));
            assertNull(scalar(connection,
                    "SELECT name FROM schema_migrations WHERE version = 5"));
            assertNull(scalar(connection,
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'managed_coop_authority'"));
        }
    }

    private static void createV4Fixture(Connection connection) throws Exception {
        execute(connection, "CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at_ms INTEGER NOT NULL)");
        execute(connection, "INSERT INTO schema_migrations VALUES (2, 'schema_v2', 1), (3, 'schema_v3', 1), (4, 'schema_v4', 1)");
        execute(connection, "CREATE TABLE npc_profiles (profile_id TEXT PRIMARY KEY, current_npc_uuid TEXT UNIQUE, role_id TEXT)");
        execute(connection, "CREATE TABLE npc_uuid_aliases (npc_uuid TEXT PRIMARY KEY, profile_id TEXT NOT NULL, is_current INTEGER NOT NULL, mapped_at_ms INTEGER NOT NULL)");
        execute(connection, "CREATE TABLE npc_snapshots (snapshot_id INTEGER PRIMARY KEY AUTOINCREMENT, profile_id TEXT NOT NULL, snapshot_type TEXT NOT NULL, payload_json TEXT NOT NULL, is_active INTEGER NOT NULL, created_at_ms INTEGER NOT NULL)");
        execute(connection, """
                CREATE TABLE coop_slots (
                    world_name TEXT NOT NULL, coop_id TEXT NOT NULL,
                    x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                    resident_slot INTEGER NOT NULL, profile_id TEXT,
                    housed_npc_uuid TEXT, last_released_npc_uuid TEXT,
                    captured_at_ms INTEGER NOT NULL, released_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL, state_snapshot_json TEXT,
                    PRIMARY KEY (world_name, coop_id, x, y, z, resident_slot)
                )
                """);
    }

    private static void insertProfile(Connection connection, String profileId, UUID uuid, String roleId) throws Exception {
        execute(connection, "INSERT INTO npc_profiles VALUES ('" + profileId + "', '" + uuid + "', '" + roleId + "')");
    }

    private static void insertCoopRow(Connection connection,
                                      int slot,
                                      String profileId,
                                      UUID housedUuid,
                                      UUID releasedUuid) throws Exception {
        String housed = housedUuid == null ? "NULL" : "'" + housedUuid + "'";
        String released = releasedUuid == null ? "NULL" : "'" + releasedUuid + "'";
        execute(connection, "INSERT INTO coop_slots VALUES ('world', 'Coop_Chicken', 1, 2, 3, " + slot
                + ", '" + profileId + "', " + housed + ", " + released + ", 1, 0, 2, '{}')");
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int count(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }
}
