package com.alechilles.alecstamework.persistence.sqlite;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteMigrationBackupServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void vacuumBackupIncludesCommittedWalDataAndNeverOverwrites() throws Exception {
        Path database = tempDir.resolve("tamework.sqlite");
        Path unrelatedWorldData = tempDir.resolve("world-region-data");
        Files.createDirectories(unrelatedWorldData);
        Files.writeString(unrelatedWorldData.resolve("region.bin"), "owned-by-hytale");
        SqliteConnectionManager connections = new SqliteConnectionManager(database);
        SqliteMigrationBackupService service = new SqliteMigrationBackupService();
        Path first;
        Path second;
        try (Connection connection = connections.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("CREATE TABLE durable (value TEXT NOT NULL)");
            statement.execute("INSERT INTO durable VALUES ('from-wal')");
            assertTrue(Files.exists(database.resolveSibling(database.getFileName() + "-wal")));
            first = service.backupBeforeVersion(database, connections, new SqliteSchemaMigrator(), 5).orElseThrow();
            second = service.backupBeforeVersion(database, connections, new SqliteSchemaMigrator(), 5).orElseThrow();
        }

        assertNotEquals(first, second);
        assertEquals(database.toAbsolutePath().normalize().getParent(), first.getParent());
        assertTrue(Files.exists(first));
        assertTrue(Files.exists(second));
        assertEquals("owned-by-hytale", Files.readString(unrelatedWorldData.resolve("region.bin")));
        assertFalse(Files.exists(tempDir.resolve("world-region-data.bak")));
        Path firstManifest = SqliteMigrationBackupService.manifestPath(first);
        assertTrue(Files.exists(firstManifest));
        JsonObject manifest = JsonParser.parseString(Files.readString(firstManifest)).getAsJsonObject();
        assertEquals("tamework_sqlite_only", manifest.get("scope").getAsString());
        assertEquals("hytale_server_operator", manifest.get("hytaleSaveBackupOwnedBy").getAsString());
        assertEquals(5, manifest.get("targetSchemaVersion").getAsInt());
        assertEquals(Files.size(first), manifest.get("snapshotSizeBytes").getAsLong());
        assertEquals(64, manifest.get("snapshotSha256").getAsString().length());
        try (Connection backup = java.sql.DriverManager.getConnection("jdbc:sqlite:" + first);
             Statement statement = backup.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT value FROM durable")) {
            assertTrue(resultSet.next());
            assertEquals("from-wal", resultSet.getString(1));
        }
    }
}
