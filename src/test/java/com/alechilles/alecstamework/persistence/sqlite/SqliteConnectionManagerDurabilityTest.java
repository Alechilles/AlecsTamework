package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteConnectionManagerDurabilityTest {
    @TempDir
    Path tempDir;

    @Test
    void everyWriterSessionUsesWalWithFullSynchronousDurability() throws Exception {
        SqliteConnectionManager manager = new SqliteConnectionManager(tempDir.resolve("durable.sqlite"));

        try (Connection connection = manager.openConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("PRAGMA journal_mode")) {
                assertEquals("wal", result.next() ? result.getString(1).toLowerCase() : "");
            }
            try (ResultSet result = statement.executeQuery("PRAGMA synchronous")) {
                assertEquals(2, result.next() ? result.getInt(1) : -1);
            }
        }
    }
}
