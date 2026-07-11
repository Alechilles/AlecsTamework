package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteMigrationBackupServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void vacuumBackupIncludesCommittedWalDataAndNeverOverwrites() throws Exception {
        Path database = tempDir.resolve("tamework.sqlite");
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
        assertTrue(Files.exists(first));
        assertTrue(Files.exists(second));
        try (Connection backup = java.sql.DriverManager.getConnection("jdbc:sqlite:" + first);
             Statement statement = backup.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT value FROM durable")) {
            assertTrue(resultSet.next());
            assertEquals("from-wal", resultSet.getString(1));
        }
    }
}
