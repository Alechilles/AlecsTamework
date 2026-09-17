package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.sqlite.SQLiteConnection;

/** One writer-lane maintenance pass that reclaims obsolete checkpoint history and file space. */
final class SqliteDatabaseCompaction {
    private static final int AUTO_VACUUM_INCREMENTAL = 2;

    private SqliteDatabaseCompaction() {
    }

    /**
     * Compacts only published and consumed internal checkpoint operations, then rebuilds SQLite
     * with incremental auto-vacuum enabled. The caller must already have drained public work.
     */
    static SqliteDatabaseCompactionResult run(Connection connection, Path databasePath, long nowMs)
            throws Exception {
        if (connection == null || databasePath == null) {
            throw new IllegalArgumentException("SQLite connection and database path are required");
        }
        if (!connection.getAutoCommit()) {
            throw new SQLException("database_compaction_requires_autocommit");
        }
        Path target = databasePath.toAbsolutePath().normalize();
        long bytesBefore = databaseBytes(target);
        requireNoOperationsInFlight(connection);
        checkpointWal(connection);
        requireVacuumHeadroom(target);

        int compactedOperations = compactCheckpointHistory(connection, nowMs);
        checkpointWal(connection);
        rebuildBesideDatabase(connection, target);
        checkpointWal(connection);
        requireQuickCheck(connection);
        return new SqliteDatabaseCompactionResult(
                bytesBefore, databaseBytes(target), compactedOperations
        );
    }

    private static int compactCheckpointHistory(Connection connection, long nowMs) throws Exception {
        long afterSequence = 0;
        int compacted = 0;
        while (true) {
            SqliteCheckpointHistoryStore.Batch batch = compactOneWindow(
                    connection, afterSequence, nowMs
            );
            compacted += batch.receipts().size();
            if (batch.nextSequence() == 0) {
                break;
            }
            afterSequence = batch.nextSequence();
        }
        return compacted;
    }

    /** Each retained-history window commits independently so a large database has bounded rollback. */
    private static SqliteCheckpointHistoryStore.Batch compactOneWindow(
            Connection connection,
            long afterSequence,
            long nowMs
    ) throws Exception {
        boolean committed = false;
        connection.setAutoCommit(false);
        try {
            SqliteCheckpointHistoryStore store = new SqliteCheckpointHistoryStore(connection);
            SqliteCheckpointHistoryStore.Batch batch = store.select(afterSequence, nowMs);
            store.compact(batch);
            connection.commit();
            committed = true;
            return batch;
        } catch (Exception failure) {
            rollbackAfterFailure(connection, failure);
            throw failure;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException restoreFailure) {
                if (committed) {
                    throw restoreFailure;
                }
            }
        }
    }

    private static void rollbackAfterFailure(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void requireNoOperationsInFlight(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT 1 FROM operation_envelope
                     WHERE phase IN ('PREPARED', 'LIVE_APPLYING', 'DURABLE',
                         'COMPENSATING', 'RETRYABLE')
                     LIMIT 1
                     """)) {
            if (rows.next()) {
                throw new SQLException("database_compaction_operations_pending");
            }
        }
    }

    /**
     * Keeps the large rebuild image on the checked save volume. The owning writer lane and
     * maintenance gate must remain held throughout: no canonical writes may occur between
     * taking this snapshot and copying it back. Existing readers keep SQLite's normal WAL safety.
     */
    static void rebuildBesideDatabase(Connection connection, Path databasePath) throws Exception {
        Path rebuilt = Files.createTempFile(databasePath.getParent(),
                databasePath.getFileName() + ".compact-", ".sqlite");
        Exception originalFailure = null;
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA auto_vacuum=INCREMENTAL");
            }
            try (PreparedStatement statement = connection.prepareStatement("VACUUM INTO ?")) {
                statement.setString(1, rebuilt.toString());
                statement.execute();
            }
            // Validate the candidate before touching the original. Do not rename a database
            // underneath live readers or manually manipulate its WAL/SHM files.
            try (Connection source = new SqliteConnectionFactory(rebuilt).openReadConnection()) {
                requireQuickCheck(source);
                requireIncrementalVacuum(source);
                copyRebuiltDatabase(source, databasePath);
            }
            requireIncrementalVacuum(connection);
        } catch (Exception failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            try {
                Files.deleteIfExists(rebuilt);
            } catch (IOException cleanupFailure) {
                if (originalFailure != null) {
                    originalFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static void copyRebuiltDatabase(Connection source, Path databasePath) throws SQLException {
        boolean[] completed = {false};
        // Xerial 3.49.1.0 restore() reads errors from the source, hiding destination failures.
        // Use backup() from the copy instead. Its busy-exhaustion result can also be zero:
        // require the synchronous successful final-step callback as positive commit evidence.
        // The callback must not throw or access either database connection.
        int result = source.unwrap(SQLiteConnection.class).getDatabase().backup(
                "main", databasePath.toString(),
                (remaining, total) -> {
                    if (remaining == 0) completed[0] = true;
                },
                100, 3, 100
        );
        if (result != 0) {
            throw new SQLException("database_compaction_copy_failed", null, result);
        }
        if (!completed[0]) {
            throw new SQLException("database_compaction_copy_busy", null, 5);
        }
    }

    private static void requireIncrementalVacuum(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (integerPragma(statement, "PRAGMA auto_vacuum") != AUTO_VACUUM_INCREMENTAL) {
                throw new SQLException("database_compaction_incremental_vacuum_not_enabled");
            }
        }
    }

    private static void checkpointWal(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (!rows.next() || rows.getInt(1) != 0) {
                throw new SQLException("database_compaction_wal_checkpoint_busy");
            }
        }
    }

    private static void requireQuickCheck(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA quick_check")) {
            if (!rows.next() || !"ok".equalsIgnoreCase(rows.getString(1)) || rows.next()) {
                throw new SQLException(
                        "[SQLITE_CORRUPT] database_compaction_quick_check_failed", null, 11
                );
            }
        }
    }

    private static int integerPragma(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new SQLException("database_compaction_pragma_result_missing");
            }
            return rows.getInt(1);
        }
    }

    private static void requireVacuumHeadroom(Path databasePath) throws SQLException {
        try {
            if (!Files.isRegularFile(databasePath)) {
                throw new SQLException("database_compaction_database_missing");
            }
            long databaseBytes = Files.size(databasePath);
            long requiredBytes = databaseBytes > Long.MAX_VALUE / 2
                    ? Long.MAX_VALUE : databaseBytes * 2;
            FileStore fileStore = Files.getFileStore(databasePath);
            if (fileStore.getUsableSpace() < requiredBytes) {
                throw new SQLException("database_compaction_insufficient_disk_space");
            }
        } catch (IOException failure) {
            throw new SQLException("database_compaction_disk_space_unavailable", failure);
        }
    }

    private static long databaseBytes(Path databasePath) throws IOException {
        return fileBytes(databasePath) + fileBytes(databasePath.resolveSibling(
                databasePath.getFileName() + "-wal"
        ));
    }

    private static long fileBytes(Path path) throws IOException {
        return Files.isRegularFile(path) ? Files.size(path) : 0;
    }
}
