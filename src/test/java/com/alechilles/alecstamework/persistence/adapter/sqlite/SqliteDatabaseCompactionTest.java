package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests for physical SQLite database reclamation. */
class SqliteDatabaseCompactionTest {
    private static final long COMPACTION_TIME = SqliteCheckpointHistoryStore.RETENTION_MS + 10_000L;

    @TempDir
    Path tempDir;

    /** Reproduces a host temp directory unavailable to VACUUM while the save drive is writable. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void compactsWhenSystemTemporaryStorageIsUnavailable() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java.toString(), "-cp", classpath,
                UnavailableTempChild.class.getName(), tempDir.toString())
                .redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Compaction child timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /** Isolates the test-only global SQLite setting from every other test connection. */
    public static final class UnavailableTempChild {
        public static void main(String[] arguments) throws Exception {
            Path root = Path.of(arguments[0]);
            Path database = root.resolve("save with ' quote.sqlite");
            seedCheckpointHistory(database, 1);
            try (Connection writer = new SqliteConnectionFactory(database).openWriterConnection();
                 Statement statement = writer.createStatement()) {
                statement.execute("CREATE TABLE retained_payload (body BLOB)");
                statement.execute("INSERT INTO retained_payload VALUES (zeroblob(16777216))");
                statement.execute("CREATE TABLE discarded_payload (body BLOB)");
                statement.execute("INSERT INTO discarded_payload VALUES (zeroblob(16777216))");
                statement.execute("DROP TABLE discarded_payload");
                Path unavailable = Files.createDirectory(root.resolve("unavailable-temp"));
                statement.execute("PRAGMA temp_store_directory='"
                        + unavailable.toString().replace("'", "''") + "'");
                Files.delete(unavailable);
                // Windows' pinned SQLite VFS uses this explicit directory without a fallback.
                // Prove the old algorithm actually fails before exercising the production fix.
                assertThrows(SQLException.class, () -> statement.execute("VACUUM"));

                SqliteDatabaseCompactionResult result = SqliteDatabaseCompaction.run(
                        writer, database, COMPACTION_TIME);

                assertTrue(result.bytesAfter() < result.bytesBefore());
                assertEquals(16777216, intValue(statement, "SELECT length(body) FROM retained_payload"));
                assertEquals(2, intPragma(statement, "PRAGMA auto_vacuum"));
                assertEquals("ok", text(statement, "PRAGMA integrity_check"));
                statement.execute("INSERT INTO retained_payload VALUES (X'1234')");
                assertEquals(2, intValue(statement, "SELECT COUNT(*) FROM retained_payload"));
            }
        }
    }

    /** Catches a new database silently retaining the non-reclaiming auto-vacuum mode. */
    @Test
    void freshWriterDatabaseUsesIncrementalAutoVacuumBeforeTablesExist() throws Exception {
        try (Connection connection = new SqliteConnectionFactory(tempDir.resolve("fresh.sqlite"))
                .openWriterConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA auto_vacuum")) {
            assertTrue(rows.next());
            assertEquals(2, rows.getInt(1));
        }
    }

    /** Catches old databases retaining checkpoint payloads or failing to reclaim their file space. */
    @Test
    void compactsConsumedCheckpointHistoryAndConvertsAnExistingDatabase() throws Exception {
        Path database = tempDir.resolve("existing.sqlite");
        seedCheckpointHistory(database, 65);

        try (Connection writer = new SqliteConnectionFactory(database).openWriterConnection()) {
            SqliteDatabaseCompactionResult result = SqliteDatabaseCompaction.run(
                    writer, database, COMPACTION_TIME
            );

            assertEquals(65, result.compactedOperations());
            assertTrue(result.bytesAfter() < result.bytesBefore());
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            assertEquals(2, intPragma(statement, "PRAGMA auto_vacuum"));
            assertEquals("{\"current\":true}", text(statement, """
                    SELECT json_payload FROM profile_extension_data
                    WHERE profile_id = 'profile' AND namespace = 'Alechilles:Tamework:EntityCheckpoint'
                    """));
            assertEquals(0, intValue(statement, """
                    SELECT COUNT(*) FROM projection_outbox
                    WHERE event_type = 'profile_extension_mutated'
                    """));
            assertEquals(65, intValue(statement, """
                    SELECT COUNT(*) FROM operation_envelope
                    WHERE payload_json LIKE '%checkpoint-operation-receipt%'
                    """));
        }
    }

    /** Catches maintenance vacuuming while an external operation can still need recovery. */
    @Test
    void refusesCompactionWhileAnOperationIsInFlight() throws Exception {
        Path database = tempDir.resolve("pending.sqlite");
        createPendingDatabase(database);

        try (Connection writer = new SqliteConnectionFactory(database).openWriterConnection()) {
            SQLException failure = assertThrows(SQLException.class,
                    () -> SqliteDatabaseCompaction.run(writer, database, COMPACTION_TIME));
            assertEquals("database_compaction_operations_pending", failure.getMessage());
        }
    }

    /** Catches converted databases reverting to reusable pages instead of returning later history to disk. */
    @Test
    void convertedDatabaseIncrementallyReclaimsLaterCheckpointHistory() throws Exception {
        Path database = tempDir.resolve("incremental.sqlite");
        seedCheckpointHistory(database, 1);
        try (Connection writer = new SqliteConnectionFactory(database).openWriterConnection()) {
            SqliteDatabaseCompaction.run(writer, database, COMPACTION_TIME);
        }
        appendCheckpointHistory(database, 100, 64);
        checkpointWal(database);
        long bytesBefore = databaseBytes(database);

        try (Connection writer = new SqliteConnectionFactory(database).openWriterConnection()) {
            writer.setAutoCommit(false);
            try {
                SqliteCheckpointHistoryStore store = new SqliteCheckpointHistoryStore(writer);
                SqliteCheckpointHistoryStore.Batch batch = store.select(0, COMPACTION_TIME);
                assertTrue(batch.receipts().size() > 0);
                store.compact(batch);
                writer.commit();
            } finally {
                writer.setAutoCommit(true);
            }
        }
        checkpointWal(database);

        assertTrue(databaseBytes(database) < bytesBefore);
    }

    /** Catches a busy WAL truncate being reported as successful while a reader still holds a snapshot. */
    @Test
    void readerBlockingWalTruncateFailsThenAllowsCompactionRetry() throws Exception {
        Path database = tempDir.resolve("reader.sqlite");
        seedCheckpointHistory(database, 2);

        try (Connection writer = new SqliteConnectionFactory(database, 1).openWriterConnection();
             Connection reader = new SqliteConnectionFactory(database, 1).openReadConnection();
             Statement readerStatement = reader.createStatement();
             ResultSet readerRows = readerStatement.executeQuery("SELECT * FROM projection_outbox")) {
            assertTrue(readerRows.next());
            SQLException failure = assertThrows(SQLException.class,
                    () -> SqliteDatabaseCompaction.run(writer, database, COMPACTION_TIME));
            assertEquals("database_compaction_wal_checkpoint_busy", failure.getMessage());
        }

        try (Connection writer = new SqliteConnectionFactory(database, 1).openWriterConnection()) {
            SqliteDatabaseCompactionResult result = SqliteDatabaseCompaction.run(
                    writer, database, COMPACTION_TIME
            );
            assertTrue(result.bytesAfter() <= result.bytesBefore());
        }
    }

    /** Catches Xerial reporting zero after an incomplete backup that exhausted its busy retries. */
    @Test
    void blockedCopyReportsFailurePreservesDataAndCanRetry() throws Exception {
        Path database = tempDir.resolve("blocked-copy.sqlite");
        seedCheckpointHistory(database, 1);
        try (Connection writer = new SqliteConnectionFactory(database, 1).openWriterConnection();
             Connection blocker = new SqliteConnectionFactory(database, 1).openWriterConnection();
             Statement locked = blocker.createStatement();
             Statement statement = writer.createStatement()) {
            locked.execute("BEGIN IMMEDIATE");
            try {
                locked.execute("UPDATE profile_extension_data SET json_payload = 'uncommitted'");
                SQLException failure = assertThrows(SQLException.class,
                        () -> SqliteDatabaseCompaction.rebuildBesideDatabase(writer, database));
                assertEquals(5, failure.getErrorCode());
                assertEquals("{\"current\":true}", text(statement,
                        "SELECT json_payload FROM profile_extension_data"));
                assertEquals(0, intPragma(statement, "PRAGMA auto_vacuum"));
                try (var files = Files.list(tempDir)) {
                    assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".compact-")),
                            "Failed maintenance must release its temporary disk space");
                }
            } finally {
                locked.execute("ROLLBACK");
            }
            SqliteDatabaseCompaction.run(writer, database, COMPACTION_TIME);
            assertEquals(2, intPragma(statement, "PRAGMA auto_vacuum"));
            assertEquals("{\"current\":true}", text(statement,
                    "SELECT json_payload FROM profile_extension_data"));
            assertEquals("ok", text(statement, "PRAGMA integrity_check"));
        }
    }

    /** Catches replacing the database file or bypassing WAL safety underneath a live read snapshot. */
    @Test
    void readerKeepsItsSnapshotAcrossTransactionalCopyBack() throws Exception {
        Path database = tempDir.resolve("reader-during-copy.sqlite");
        seedCheckpointHistory(database, 2);
        try (Connection writer = new SqliteConnectionFactory(database, 1).openWriterConnection();
             Statement statement = writer.createStatement()) {
            try (Connection reader = new SqliteConnectionFactory(database, 1).openReadConnection();
                 Statement reading = reader.createStatement();
                 ResultSet rows = reading.executeQuery(
                         "SELECT operation_id FROM operation_envelope ORDER BY operation_id")) {
                assertTrue(rows.next());
                assertEquals("checkpoint-0", rows.getString(1));
                SqliteDatabaseCompaction.rebuildBesideDatabase(writer, database);
                assertEquals(2, intPragma(statement, "PRAGMA auto_vacuum"));
                assertTrue(rows.next());
                assertEquals("checkpoint-1", rows.getString(1));
                assertTrue(rows.next());
                assertEquals("keep", rows.getString(1));
            }
            SqliteDatabaseCompaction.run(writer, database, COMPACTION_TIME);
            assertEquals("ok", text(statement, "PRAGMA integrity_check"));
            assertEquals("{\"current\":true}", text(statement,
                    "SELECT json_payload FROM profile_extension_data"));
        }
    }

    private static void seedCheckpointHistory(Path database, int checkpoints) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            createCheckpointTables(statement);
            statement.execute("""
                    INSERT INTO profile_extension_data(
                        profile_id, namespace, data_key, json_payload, revision
                    ) VALUES (
                        'profile', 'Alechilles:Tamework:EntityCheckpoint', 'entity',
                        '{"current":true}', 100
                    )
                    """);
            statement.execute("""
                    INSERT INTO projection_checkpoint(consumer_id, acknowledged_sequence)
                    VALUES ('profile_extension_index', 1000)
                    """);
            appendCheckpointHistory(statement, 0, checkpoints);
            statement.execute("""
                    INSERT INTO operation_envelope(
                        operation_id, operation_kind, idempotency_key, payload_version,
                        payload_json, phase, published_at_ms
                    ) VALUES ('keep', 'other', 'keep', 1, '{}', 'PUBLISHED', 1)
                    """);
            statement.execute("""
                    INSERT INTO projection_outbox(operation_id, event_type, aggregate_revision)
                    VALUES ('keep', 'other', 1)
                    """);
        }
    }

    private static void appendCheckpointHistory(Path database, int firstIndex, int checkpoints)
            throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            appendCheckpointHistory(statement, firstIndex, checkpoints);
        }
    }

    private static void appendCheckpointHistory(Statement statement, int firstIndex, int checkpoints)
            throws SQLException {
        String largeJson = "x".repeat(32 * 1024);
        for (int offset = 0; offset < checkpoints; offset++) {
            int index = firstIndex + offset;
            String operationId = "checkpoint-" + index;
            String payload = "{\"profileId\":\"profile\",\"namespace\":\"Alechilles:Tamework:EntityCheckpoint\","
                    + "\"dataKey\":\"entity\",\"action\":\"PUT\",\"body\":\""
                    + largeJson + "\"}";
            statement.execute("""
                    INSERT INTO operation_envelope(
                        operation_id, operation_kind, idempotency_key, payload_version,
                        payload_json, phase, published_at_ms
                    ) VALUES (
                        '%s', 'profile_extension_mutation',
                        'companion-entity-checkpoint:v1:%s', 1,
                        '%s',
                        'PUBLISHED', 1
                    )
                    """.formatted(operationId, index, payload));
            statement.execute("""
                    INSERT INTO projection_outbox(
                        operation_id, event_type, aggregate_revision
                    ) VALUES ('%s', 'profile_extension_mutated', 1)
                    """.formatted(operationId));
        }
    }

    private static void createPendingDatabase(Path database) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE operation_envelope (
                        operation_id TEXT PRIMARY KEY,
                        phase TEXT NOT NULL
                    )
                    """);
            statement.execute("INSERT INTO operation_envelope(operation_id, phase) VALUES ('pending', 'PREPARED')");
        }
    }

    private static void createCheckpointTables(Statement statement) throws SQLException {
        statement.execute("""
                CREATE TABLE operation_envelope (
                    operation_id TEXT PRIMARY KEY,
                    operation_kind TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    payload_version INTEGER NOT NULL,
                    payload_json TEXT NOT NULL,
                    phase TEXT NOT NULL,
                    published_at_ms INTEGER
                )
                """);
        statement.execute("""
                CREATE TABLE projection_outbox (
                    event_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                    operation_id TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    aggregate_revision INTEGER NOT NULL
                )
                """);
        statement.execute("""
                CREATE TABLE projection_checkpoint (
                    consumer_id TEXT PRIMARY KEY,
                    acknowledged_sequence INTEGER NOT NULL
                )
                """);
        statement.execute("""
                CREATE TABLE profile_extension_data (
                    profile_id TEXT NOT NULL,
                    namespace TEXT NOT NULL,
                    data_key TEXT NOT NULL,
                    json_payload TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    PRIMARY KEY (profile_id, namespace, data_key)
                )
                """);
        statement.execute("""
                CREATE TABLE operation_participant (
                    operation_id TEXT NOT NULL,
                    scope_type TEXT NOT NULL,
                    scope_key TEXT NOT NULL
                )
                """);
        statement.execute("""
                CREATE TABLE persistence_quarantine (
                    scope_type TEXT NOT NULL,
                    scope_key TEXT NOT NULL,
                    state TEXT NOT NULL
                )
                """);
    }

    private static int intPragma(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static int intValue(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static String text(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static void checkpointWal(Path database) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database).openWriterConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    private static long databaseBytes(Path database) throws Exception {
        long walBytes = java.nio.file.Files.isRegularFile(database.resolveSibling(
                database.getFileName() + "-wal"
        )) ? java.nio.file.Files.size(database.resolveSibling(database.getFileName() + "-wal")) : 0;
        return java.nio.file.Files.size(database) + walBytes;
    }
}
