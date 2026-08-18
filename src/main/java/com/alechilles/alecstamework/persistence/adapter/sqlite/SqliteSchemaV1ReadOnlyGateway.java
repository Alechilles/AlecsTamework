package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Verifies the complete replacement schema on a read-only connection.
 *
 * <p>This gateway has no writer or schema-manager dependency. It compares the
 * complete SQLite object set with the bundled schema, then checks persisted
 * integrity and foreign-key consistency. Activation probes use it before they
 * decide whether a writer may be constructed.</p>
 */
public final class SqliteSchemaV1ReadOnlyGateway {
    private static final String RESOURCE = "/persistence/schema/v1.sql";

    private SqliteSchemaV1ReadOnlyGateway() {
    }

    /**
     * Verifies one existing replacement database without changing it.
     *
     * @throws SQLException when the schema, history, or stored rows are not
     *                      safe for the replacement persistence runtime
     */
    public static void verify(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        try {
            String script = loadScript();
            if (!schemaObjects(connection).equals(expectedSchemaObjects(script))) {
                throw new SQLException("replacement_schema_definition_mismatch");
            }
            verifyHistory(connection, sha256(script));
            requireSingleValue(connection, "PRAGMA quick_check(1)", "ok",
                    "replacement_integrity_check_failed");
            requireNoRows(connection, "PRAGMA foreign_key_check",
                    "replacement_foreign_key_check_failed");
        } catch (SQLException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SQLException("replacement_schema_unverified", failure);
        }
    }

    private static void verifyHistory(Connection connection, String hash)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT version, lineage, schema_hash
                     FROM schema_history
                     """)) {
            if (!rows.next()
                    || rows.getInt("version") != SqliteSchemaV1Manager.VERSION
                    || !SqliteSchemaV1Manager.LINEAGE.equals(
                    rows.getString("lineage"))
                    || !hash.equals(rows.getString("schema_hash"))
                    || rows.next()) {
                throw new SQLException("replacement_schema_history_mismatch");
            }
        }
    }

    private static Map<String, String> expectedSchemaObjects(String script)
            throws Exception {
        try (Connection authority = DriverManager.getConnection(
                "jdbc:sqlite::memory:")) {
            for (String sql : SqlScriptParser.statements(script)) {
                if (!sql.isBlank()) {
                    try (Statement statement = authority.createStatement()) {
                        statement.execute(sql);
                    }
                }
            }
            return schemaObjects(authority);
        }
    }

    private static Map<String, String> schemaObjects(Connection connection)
            throws SQLException {
        HashMap<String, String> objects = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT type, name, sql
                     FROM sqlite_master
                     WHERE name NOT LIKE 'sqlite_%'
                     ORDER BY type, name
                     """)) {
            while (rows.next()) {
                String sql = rows.getString("sql");
                if (sql == null) {
                    throw new SQLException("replacement_schema_sql_missing");
                }
                objects.put(
                        rows.getString("type") + ":" + rows.getString("name"),
                        normalizeDdl(sql)
                );
            }
        }
        return Map.copyOf(objects);
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

    private static String loadScript() throws Exception {
        try (InputStream stream = SqliteSchemaV1ReadOnlyGateway.class
                .getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new SQLException("replacement_schema_resource_missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace('\r', '\n');
        }
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static String normalizeDdl(String sql) {
        String normalized = sql.trim().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        return normalized.endsWith(";")
                ? normalized.substring(0, normalized.length() - 1).trim()
                : normalized;
    }
}
