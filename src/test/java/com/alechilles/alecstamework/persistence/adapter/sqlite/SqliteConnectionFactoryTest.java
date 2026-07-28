package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests for replacement database naming, sessions, and failure classification. */
class SqliteConnectionFactoryTest {
    @TempDir
    Path tempDir;

    @Test
    void replacementFilenameIsSeparateFromTheLegacySource() {
        assertEquals(tempDir.resolve("tamework-state.sqlite").toAbsolutePath(),
                PersistenceFiles.replacementDatabase(tempDir));
        assertEquals(tempDir.resolve("tamework.sqlite").toAbsolutePath(),
                PersistenceFiles.legacyDatabase(tempDir));
    }

    @Test
    void writerCreatesOnlyTheReplacementTargetWithVerifiedPragmas() throws Exception {
        Path target = PersistenceFiles.replacementDatabase(tempDir);
        Path legacy = PersistenceFiles.legacyDatabase(tempDir);
        SqliteConnectionFactory factory = new SqliteConnectionFactory(target, 321);

        try (Connection connection = factory.openWriterConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE example (id INTEGER PRIMARY KEY)");
            assertEquals("wal", textPragma(statement, "PRAGMA journal_mode"));
            assertEquals(2, intPragma(statement, "PRAGMA synchronous"));
            assertEquals(1, intPragma(statement, "PRAGMA foreign_keys"));
            assertEquals(321, intPragma(statement, "PRAGMA busy_timeout"));
        }

        assertTrue(Files.isRegularFile(target));
        assertFalse(Files.exists(legacy));
    }

    @Test
    void readerRequiresAnExistingDatabaseAndCannotWrite() throws Exception {
        Path target = PersistenceFiles.replacementDatabase(tempDir);
        SqliteConnectionFactory factory = new SqliteConnectionFactory(target);

        assertThrows(SQLException.class, factory::openReadConnection);
        try (Connection writer = factory.openWriterConnection();
             Statement statement = writer.createStatement()) {
            statement.execute("CREATE TABLE example (id INTEGER PRIMARY KEY)");
        }

        try (Connection reader = factory.openReadConnection();
             Statement statement = reader.createStatement()) {
            assertEquals(1, intPragma(statement, "PRAGMA query_only"));
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO example(id) VALUES (1)"));
        }
    }

    @Test
    void classifiesRetryableBusySeparatelyFromCorruptionAndSchemaFailures() {
        assertEquals(StorageFailureKind.BUSY,
                SqliteFailureClassifier.classify(
                        new SQLException("[SQLITE_BUSY] database is locked", null, 5),
                        "load_profile"
                ).kind());
        assertTrue(SqliteFailureClassifier.classify(
                new SQLException("[SQLITE_BUSY] database is locked", null, 5),
                "load_profile"
        ).retryable());
        assertEquals(StorageFailureKind.CORRUPT,
                SqliteFailureClassifier.classify(
                        new SQLException("[SQLITE_CORRUPT] database disk image is malformed", null, 11),
                        "load_profile"
                ).kind());
        assertEquals(StorageFailureKind.SCHEMA,
                SqliteFailureClassifier.classify(
                        new SQLException("no such table: companion_profile", null, 1),
                        "load_profile"
                ).kind());
    }

    private static String textPragma(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static int intPragma(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }
}
