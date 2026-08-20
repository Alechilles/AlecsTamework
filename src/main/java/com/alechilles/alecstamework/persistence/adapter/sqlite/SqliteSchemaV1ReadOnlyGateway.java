package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** Verifies exact replacement schema version 1 or version 2 without mutation. */
public final class SqliteSchemaV1ReadOnlyGateway {
    private SqliteSchemaV1ReadOnlyGateway() {
    }

    /**
     * Verifies one existing replacement database without changing it.
     *
     * <p>The historical gateway remains the activation entry point. It accepts
     * the exact immutable version-1 shape and the exact migrated version-2
     * shape, so a valid target remains visible to read-only startup probes.</p>
     *
     * @throws SQLException when the schema, history, or stored rows are unsafe
     *                      for the replacement persistence runtime
     */
    public static void verify(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        if (historyVersion(connection) == SqliteSchemaV2Manager.VERSION) {
            SqliteSchemaV2ReadOnlyGateway.verify(connection);
            return;
        }
        verifyVersion1(connection);
    }

    private static void verifyVersion1(Connection connection) throws SQLException {
        try {
            SqliteSchemaInspector.SchemaInspection inspection =
                    SqliteSchemaInspector.inspect(connection);
            if (!inspection.objects().equals(
                    SqliteSchemaV1Manager.requiredSchemaObjects())) {
                throw new SQLException("replacement_schema_object_mismatch");
            }
            if (!inspection.definitions().equals(
                    SqliteSchemaV1Manager.requiredSchemaDefinitions())) {
                throw new SQLException("replacement_schema_definition_mismatch");
            }
            verifyHistory(connection);
            requireSingleValue(
                    connection,
                    "PRAGMA quick_check(1)",
                    "ok",
                    "replacement_integrity_check_failed"
            );
            requireNoRows(
                    connection,
                    "PRAGMA foreign_key_check",
                    "replacement_foreign_key_check_failed"
            );
        } catch (SQLException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SQLException("replacement_schema_unverified", failure);
        }
    }

    private static void verifyHistory(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT version, lineage, schema_hash
                     FROM schema_history
                     """)) {
            if (!rows.next()
                    || rows.getInt("version") != SqliteSchemaV1Manager.VERSION
                    || !SqliteSchemaV1Manager.LINEAGE.equals(
                    rows.getString("lineage"))
                    || !SqliteSchemaV1Manager.bundledHash().equals(
                    rows.getString("schema_hash"))
                    || rows.next()) {
                throw new SQLException("replacement_schema_history_mismatch");
            }
        }
    }

    private static int historyVersion(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT version FROM schema_history"
             )) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static void requireSingleValue(
            Connection connection,
            String sql,
            String expected,
            String failureCode
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()
                    || !expected.equalsIgnoreCase(rows.getString(1))
                    || rows.next()) {
                throw new SQLException(failureCode);
            }
        }
    }

    private static void requireNoRows(
            Connection connection,
            String sql,
            String failureCode
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (rows.next()) {
                throw new SQLException(failureCode);
            }
        }
    }
}
