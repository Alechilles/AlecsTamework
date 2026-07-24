package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.annotation.Nonnull;

/** Adds durable capture-source spending and refund evidence. */
final class SqliteSchemaV9Migration {

    void apply(@Nonnull Connection connection) throws Exception {
        ensureCaptureAttemptExtensions(connection);
        try (Statement statement = connection.createStatement()) {
            dropObsoleteBondedVessels(statement);
            createCaptureSourceRefunds(statement);
        }
    }

    private void createCaptureSourceRefunds(Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE IF NOT EXISTS capture_source_refund_claims (
                    attempt_id TEXT PRIMARY KEY REFERENCES capture_attempts(attempt_id)
                        ON DELETE CASCADE,
                    owner_uuid TEXT NOT NULL,
                    item_id TEXT NOT NULL,
                    quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity = 1),
                    state TEXT NOT NULL CHECK (state IN ('PENDING','DELIVERED')),
                    reason TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL
                )
                """);
        statement.execute("""
                CREATE INDEX IF NOT EXISTS idx_capture_source_refunds_owner_state
                ON capture_source_refund_claims(owner_uuid, state, created_at_ms)
                """);
    }

    private void ensureCaptureAttemptExtensions(Connection connection) throws Exception {
        addColumnIfMissing(connection, "capture_attempts", "source_consumption",
                "TEXT NOT NULL DEFAULT 'SUCCESS_ONLY' CHECK "
                        + "(source_consumption IN ('SUCCESS_ONLY','RESOLVED_ATTEMPT'))");
        addColumnIfMissing(connection, "capture_attempts", "success_disposition",
                "TEXT NOT NULL DEFAULT 'CAPTURED_ITEM' CHECK "
                        + "(success_disposition = 'CAPTURED_ITEM')");
        addColumnIfMissing(connection, "capture_attempts", "source_spend_state",
                "TEXT NOT NULL DEFAULT 'NOT_REQUIRED' CHECK "
                        + "(source_spend_state IN ('NOT_REQUIRED','PENDING','CONSUMED'))");
        addColumnIfMissing(connection, "capture_attempts", "source_spend_before_fingerprint", "TEXT");
        addColumnIfMissing(connection, "capture_attempts", "source_spend_after_fingerprint", "TEXT");
        addColumnIfMissing(connection, "capture_attempts", "source_spend_receipted_at_ms",
                "INTEGER NOT NULL DEFAULT 0 CHECK (source_spend_receipted_at_ms >= 0)");
        addColumnIfMissing(connection, "capture_attempts", "source_spend_at_ms",
                "INTEGER NOT NULL DEFAULT 0 CHECK (source_spend_at_ms >= 0)");
    }

    private void addColumnIfMissing(Connection connection, String table, String column,
                                    String definition) throws Exception {
        if (hasColumn(connection, table, column)) return;
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean hasColumn(Connection connection, String table, String column) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) return true;
            }
            return false;
        }
    }

    private void dropObsoleteBondedVessels(Statement statement) throws Exception {
        statement.execute("DROP TABLE IF EXISTS bonded_vessel_operations");
        statement.execute("DROP TABLE IF EXISTS bonded_vessel_bindings");
    }

}
