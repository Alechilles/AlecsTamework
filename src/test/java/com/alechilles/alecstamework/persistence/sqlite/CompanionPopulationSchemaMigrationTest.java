package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationSchemaMigrationTest {
    private static final int RESERVED_SCHEMA_VERSION_V5 = 5;

    @TempDir
    Path tempDir;

    @Test
    void v6CreatesPopulationJournalCoverageAndRequiredIndexes() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve("population.sqlite"));
        SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();

        try (Connection connection = connections.openConnection()) {
            migrator.migrate(connection);
            migrator.migrate(connection);

            assertTrue(migrator.isVersionApplied(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V6));
            assertEquals(
                    Set.of(
                            "companion_population_state",
                            "companion_population_operations",
                            "companion_population_reconciliation",
                            "companion_population_reconciliation_evidence",
                            "companion_population_scan_session"
                    ),
                    existingTables(connection)
            );
            Set<String> indexes = existingIndexes(connection);
            assertTrue(indexes.contains("idx_companion_population_scope"));
            assertTrue(indexes.contains("idx_companion_population_physical_chunk"));
            assertTrue(indexes.contains("idx_companion_population_operations_state"));
            assertTrue(indexes.contains("uq_companion_population_nonterminal_profile"));
            assertTrue(indexes.contains("idx_companion_population_reconciliation_state"));
            assertTrue(indexes.contains("idx_companion_population_evidence_identity"));
            assertTrue(indexes.contains("idx_companion_population_evidence_source"));
        }
    }

    @Test
    void repeatV6MigrationRepairsDurableEvidenceAndScanSessionTables() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve("v6-evidence-repair.sqlite"));
        SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            migrator.migrate(connection);
            statement.execute("DROP TABLE companion_population_reconciliation_evidence");
            statement.execute("DROP TABLE companion_population_scan_session");

            assertFalse(tableExists(connection, "companion_population_reconciliation_evidence"));
            assertFalse(tableExists(connection, "companion_population_scan_session"));
            assertTrue(migrator.isVersionApplied(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V6));

            migrator.migrate(connection);

            assertTrue(tableExists(connection, "companion_population_reconciliation_evidence"));
            assertTrue(tableExists(connection, "companion_population_scan_session"));
            assertTrue(existingIndexes(connection).contains("idx_companion_population_evidence_identity"));
        }
    }

    @Test
    void populationStateRequiresCanonicalProfileAndCompletePhysicalCoordinate() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve("constraints.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            assertThrows(SQLException.class, () -> insertPopulationState(
                    connection,
                    "missing-profile",
                    "ACTIVE",
                    "default",
                    1,
                    2
            ));

            String profileId = insertProfile(connection);
            assertThrows(SQLException.class, () -> insertPopulationState(
                    connection,
                    profileId,
                    "ACTIVE",
                    "default",
                    1,
                    null
            ));
            insertPopulationState(connection, profileId, "CAPTURED", null, null, null);
        }
    }

    @Test
    void onlyOneNonterminalOperationMayOwnAProfile() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve("operation-unique.sqlite"));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
            String profileId = insertProfile(connection);
            insertOperation(connection, profileId, "op-a", "PREPARED");
            assertThrows(SQLException.class, () -> insertOperation(connection, profileId, "op-b", "APPLYING"));
            insertOperation(connection, profileId, "op-c", "COMMITTED");
        }
    }

    @Test
    void surroundingTransactionCanRollBackTheEntireMigration() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve("rollback.sqlite"));
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            new SqliteSchemaMigrator().migrate(connection);
            connection.rollback();
            connection.setAutoCommit(true);

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'companion_population_state'"
            );
                 ResultSet resultSet = statement.executeQuery()) {
                assertFalse(resultSet.next());
            }
        }
    }

    @Test
    void legacyProfilesAreConservativelyBackfilledAndRepeatMigrationRepairsMissingRows() throws Exception {
        SqliteConnectionManager connections = new SqliteConnectionManager(tempDir.resolve("backfill.sqlite"));
        SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
        try (Connection connection = connections.openConnection()) {
            migrator.migrate(connection);
            String profileId = insertProfile(connection);
            try (PreparedStatement state = connection.prepareStatement(
                    """
                    INSERT INTO profile_states (
                        profile_id, capture_active, death_active, lost_active, in_coop, updated_at_ms
                    ) VALUES (?, 0, 1, 0, 0, 1)
                    """
            )) {
                state.setString(1, profileId);
                state.executeUpdate();
            }

            migrator.migrate(connection);

            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    SELECT lifecycle_state, physical_world_name, revision
                    FROM companion_population_state
                    WHERE profile_id = ?
                    """
            )) {
                statement.setString(1, profileId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals("DEAD_REVIVABLE", resultSet.getString("lifecycle_state"));
                    assertNull(resultSet.getString("physical_world_name"));
                    assertEquals(0L, resultSet.getLong("revision"));
                }
            }
        }
    }

    @Test
    void everySupportedPriorSchemaUpgradesThroughV6WithoutLosingProfiles() throws Exception {
        for (int priorVersion : new int[]{2, 3, 4, RESERVED_SCHEMA_VERSION_V5}) {
            SqliteConnectionManager connections = new SqliteConnectionManager(
                    tempDir.resolve("upgrade-v" + priorVersion + ".sqlite")
            );
            SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
            try (Connection connection = connections.openConnection()) {
                migrator.migrateThrough(connection, priorVersion);
                String profileId = insertProfile(connection);
                String ownerUuid = UUID.randomUUID().toString();
                String worldName = "world-v" + priorVersion;
                setProfileOwnership(connection, profileId, ownerUuid, worldName);
                if (priorVersion == RESERVED_SCHEMA_VERSION_V5) {
                    installReservedV5Fixture(connection, migrator);
                }

                migrator.migrate(connection);

                assertTrue(migrator.isVersionApplied(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V2));
                assertTrue(migrator.isVersionApplied(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V3));
                assertTrue(migrator.isVersionApplied(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V4));
                assertTrue(migrator.isVersionApplied(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V6));
                assertEquals(
                        priorVersion == RESERVED_SCHEMA_VERSION_V5,
                        migrator.isVersionApplied(connection, RESERVED_SCHEMA_VERSION_V5)
                );
                assertBackfilledProfile(connection, profileId, worldName);
                assertEquals(ownerUuid, profileOwner(connection, profileId));
                if (priorVersion == RESERVED_SCHEMA_VERSION_V5) {
                    assertEquals("schema_v5_external_fixture", migrationName(
                            connection, RESERVED_SCHEMA_VERSION_V5
                    ));
                    assertEquals(1, scalarInt(connection, "SELECT fixture_value FROM coop_v5_integrity_marker"));
                }
            }
        }
    }

    @Test
    void v6CoordinatesWithBothPresentAndAbsentReservedV5MarkerAndTable() throws Exception {
        SqliteSchemaMigrator migrator = new SqliteSchemaMigrator();
        for (boolean v5Present : new boolean[]{false, true}) {
            SqliteConnectionManager connections = new SqliteConnectionManager(
                    tempDir.resolve("v5-" + (v5Present ? "present" : "absent") + ".sqlite")
            );
            try (Connection connection = connections.openConnection()) {
                migrator.migrateThrough(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V4);
                if (v5Present) {
                    installReservedV5Fixture(connection, migrator);
                }

                migrator.migrate(connection);

                assertTrue(migrator.isVersionApplied(connection, SqliteSchemaMigrator.SCHEMA_VERSION_V6));
                assertEquals(
                        v5Present,
                        migrator.isVersionApplied(connection, RESERVED_SCHEMA_VERSION_V5)
                );
                assertEquals(v5Present, tableExists(connection, "coop_v5_integrity_marker"));
                if (v5Present) {
                    assertEquals("schema_v5_external_fixture", migrationName(
                            connection, RESERVED_SCHEMA_VERSION_V5
                    ));
                    assertEquals(1, scalarInt(connection, "SELECT fixture_value FROM coop_v5_integrity_marker"));
                }
            }
        }
    }

    private static Set<String> existingTables(Connection connection) throws Exception {
        Set<String> wanted = Set.of(
                "companion_population_state",
                "companion_population_operations",
                "companion_population_reconciliation",
                "companion_population_reconciliation_evidence",
                "companion_population_scan_session"
        );
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
        );
             ResultSet resultSet = statement.executeQuery()) {
            Stream.Builder<String> names = Stream.builder();
            while (resultSet.next()) {
                String name = resultSet.getString(1);
                if (wanted.contains(name)) {
                    names.add(name);
                }
            }
            return names.build().collect(Collectors.toSet());
        }
    }

    private static void installReservedV5Fixture(
            Connection connection,
            SqliteSchemaMigrator migrator
    ) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE coop_v5_integrity_marker (fixture_value INTEGER NOT NULL)");
            statement.execute("INSERT INTO coop_v5_integrity_marker (fixture_value) VALUES (1)");
        }
        migrator.recordMigration(
                connection,
                RESERVED_SCHEMA_VERSION_V5,
                "schema_v5_external_fixture"
        );
    }

    private static void setProfileOwnership(
            Connection connection,
            String profileId,
            String ownerUuid,
            String worldName
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE npc_profiles SET owner_uuid = ?, last_world_name = ? WHERE profile_id = ?"
        )) {
            statement.setString(1, ownerUuid);
            statement.setString(2, worldName);
            statement.setString(3, profileId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertBackfilledProfile(
            Connection connection,
            String profileId,
            String worldName
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT ownership_world_name, lifecycle_state, source
                FROM companion_population_state
                WHERE profile_id = ?
                """
        )) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(worldName, resultSet.getString("ownership_world_name"));
                assertEquals("UNKNOWN_DORMANT", resultSet.getString("lifecycle_state"));
                assertEquals("schema_v6_legacy_backfill", resultSet.getString("source"));
                assertFalse(resultSet.next());
            }
        }
    }

    private static String profileOwner(Connection connection, String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_uuid FROM npc_profiles WHERE profile_id = ?"
        )) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        }
    }

    private static String migrationName(Connection connection, int version) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM schema_migrations WHERE version = ?"
        )) {
            statement.setInt(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        }
    }

    private static int scalarInt(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?"
        )) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static Set<String> existingIndexes(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type = 'index'"
        );
             ResultSet resultSet = statement.executeQuery()) {
            Stream.Builder<String> names = Stream.builder();
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
            return names.build().collect(Collectors.toSet());
        }
    }

    private static String insertProfile(Connection connection) throws Exception {
        String profileId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO npc_profiles (
                    profile_id, current_npc_uuid, created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, ?, ?, ?)
                """
        )) {
            statement.setString(1, profileId);
            statement.setString(2, UUID.randomUUID().toString());
            statement.setLong(3, now);
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
        return profileId;
    }

    private static void insertPopulationState(Connection connection,
                                              String profileId,
                                              String lifecycle,
                                              String world,
                                              Integer chunkX,
                                              Integer chunkZ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO companion_population_state (
                    profile_id, lifecycle_state, physical_world_name,
                    physical_chunk_x, physical_chunk_z, revision, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, 0, 1, 1)
                """
        )) {
            statement.setString(1, profileId);
            statement.setString(2, lifecycle);
            statement.setString(3, world);
            if (chunkX == null) {
                statement.setNull(4, java.sql.Types.INTEGER);
            } else {
                statement.setInt(4, chunkX);
            }
            if (chunkZ == null) {
                statement.setNull(5, java.sql.Types.INTEGER);
            } else {
                statement.setInt(5, chunkZ);
            }
            statement.executeUpdate();
        }
    }

    private static void insertOperation(Connection connection,
                                        String profileId,
                                        String operationId,
                                        String state) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO companion_population_operations (
                    operation_id, profile_id, operation_type, state, expected_revision,
                    old_state_json, new_state_json, created_at_ms, updated_at_ms
                ) VALUES (?, ?, 'TEST', ?, 0, '{}', '{}', 1, 1)
                """
        )) {
            statement.setString(1, operationId);
            statement.setString(2, profileId);
            statement.setString(3, state);
            statement.executeUpdate();
        }
    }
}
