package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** Verifies an exact version-2 replacement schema without opening a writer. */
public final class SqliteSchemaV2ReadOnlyGateway {
    private SqliteSchemaV2ReadOnlyGateway() {
    }

    /**
     * Verifies one existing version-2 replacement database without changing it.
     *
     * @throws SQLException when the schema, history, or stored rows are unsafe
     *                      for the replacement persistence runtime
     */
    public static void verify(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        try {
            SqliteSchemaInspector.SchemaInspection inspection =
                    SqliteSchemaInspector.inspect(connection);
            if (!inspection.objects().equals(
                    SqliteSchemaV2Manager.requiredSchemaObjects())) {
                throw new SQLException("replacement_schema_object_mismatch");
            }
            if (!inspection.definitions().equals(
                    SqliteSchemaV2Manager.requiredSchemaDefinitions())) {
                throw new SQLException("replacement_schema_definition_mismatch");
            }
            verifyHistory(connection);
            verifyIntegrity(connection);
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
                    || rows.getInt("version") != SqliteSchemaV2Manager.VERSION
                    || !SqliteSchemaV2Manager.LINEAGE.equals(
                    rows.getString("lineage"))
                    || !SqliteSchemaV2Manager.bundledHash().equals(
                    rows.getString("schema_hash"))
                    || rows.next()) {
                throw new SQLException("replacement_schema_history_mismatch");
            }
        }
    }

    private static void verifyIntegrity(Connection connection)
            throws SQLException {
        requireSingleValue(
                connection,
                "PRAGMA integrity_check",
                "ok",
                "replacement_integrity_check_failed"
        );
        requireNoRows(
                connection,
                "PRAGMA foreign_key_check",
                "replacement_foreign_key_check_failed"
        );
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
