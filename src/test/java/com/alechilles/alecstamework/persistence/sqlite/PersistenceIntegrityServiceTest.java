package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceIntegrityServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void cleanMigratedDatabasePassesEveryCheck() throws Exception {
        SqliteConnectionManager connections = migrated("clean.sqlite");

        PersistenceIntegrityService.IntegrityReport report =
                new PersistenceIntegrityService(connections).inspect();

        assertEquals(PersistenceIntegrityService.ReportStatus.COMPLETE, report.status());
        assertTrue(report.isClean());
    }

    @Test
    void reportsCurrentAliasAndForeignKeyViolations() throws Exception {
        SqliteConnectionManager connections = migrated("invalid.sqlite");
        try (Connection connection = connections.openConnection()) {
            insertProfile(connection, "profile-a", uuid(1));
            insertAlias(connection, "profile-a", uuid(1), true);
            insertAlias(connection, "profile-a", uuid(2), true);
        }
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.execute("INSERT INTO npc_uuid_aliases VALUES ('"
                    + uuid(3) + "', 'missing-profile', 0, 1)");
        }

        PersistenceIntegrityService.IntegrityReport report =
                new PersistenceIntegrityService(connections).inspect();

        assertEquals(PersistenceIntegrityService.ReportStatus.COMPLETE, report.status());
        assertTrue(report.issues().stream().anyMatch(
                issue -> issue.id().equals("multiple_current_aliases")
                        && issue.affectedGroups() == 1L));
        assertTrue(report.issues().stream().anyMatch(
                issue -> issue.id().equals("foreign_key_violation")
                        && issue.affectedGroups() == 1L));
    }

    private SqliteConnectionManager migrated(String fileName) throws Exception {
        SqliteConnectionManager connections =
                new SqliteConnectionManager(tempDir.resolve(fileName));
        try (Connection connection = connections.openConnection()) {
            new SqliteSchemaMigrator().migrate(connection);
        }
        return connections;
    }

    private void insertProfile(Connection connection, String profileId, UUID currentUuid)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO npc_profiles (
                  profile_id, current_npc_uuid, created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, 1, 1, 1)
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, currentUuid.toString());
            statement.executeUpdate();
        }
    }

    private void insertAlias(Connection connection, String profileId, UUID npcUuid,
                             boolean current) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO npc_uuid_aliases VALUES (?, ?, ?, 1)")) {
            statement.setString(1, npcUuid.toString());
            statement.setString(2, profileId);
            statement.setInt(3, current ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private UUID uuid(long value) {
        return new UUID(0L, value);
    }
}
