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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPopulationSchemaMigrationTest {
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
                            "companion_population_reconciliation"
                    ),
                    existingTables(connection)
            );
            Set<String> indexes = existingIndexes(connection);
            assertTrue(indexes.contains("idx_companion_population_scope"));
            assertTrue(indexes.contains("idx_companion_population_physical_chunk"));
            assertTrue(indexes.contains("idx_companion_population_operations_state"));
            assertTrue(indexes.contains("uq_companion_population_nonterminal_profile"));
            assertTrue(indexes.contains("idx_companion_population_reconciliation_state"));
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

    private static Set<String> existingTables(Connection connection) throws Exception {
        Set<String> wanted = Set.of(
                "companion_population_state",
                "companion_population_operations",
                "companion_population_reconciliation"
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
