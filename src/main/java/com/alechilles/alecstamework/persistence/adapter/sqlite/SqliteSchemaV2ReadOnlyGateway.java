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
 * Verifies the complete replacement schema v2 on a read-only connection.
 *
 * <p>The gateway has no writer or schema-manager dependency. Activation probes
 * use it before constructing the persistence runtime.</p>
 */
public final class SqliteSchemaV2ReadOnlyGateway {
    private static final String V2_RESOURCE = "/persistence/schema/v2.sql";
    private static final String V1_RESOURCE = "/persistence/schema/v1.sql";

    private SqliteSchemaV2ReadOnlyGateway() {
    }

    /**
     * Verifies one existing replacement database without changing it.
     *
     * @throws SQLException when schema, history, or persisted integrity is unsafe
     */
    public static void verify(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        try {
            String v2Script = loadScript(V2_RESOURCE);
            String v1Script = loadScript(V1_RESOURCE);
            if (!schemaObjects(connection).equals(
                    expectedSchemaObjects(v2Script)
            )) {
                throw new SQLException(
                        "replacement_schema_definition_mismatch"
                );
            }
            verifyHistory(
                    connection,
                    sha256(v1Script),
                    sha256(v2Script)
            );
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

    private static void verifyHistory(
            Connection connection, String v1Hash, String v2Hash
    ) throws SQLException {
        int count = 0;
        int previous = 0;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT version, lineage, schema_hash
                     FROM schema_history
                     ORDER BY rowid
                     """)) {
            while (rows.next()) {
                count++;
                int version = rows.getInt("version");
                if (!SqliteSchemaV2Manager.LINEAGE.equals(
                        rows.getString("lineage"))
                        || (version == 1 && !v1Hash.equals(
                        rows.getString("schema_hash")))
                        || (version == 2 && !v2Hash.equals(
                        rows.getString("schema_hash")))
                        || (version != 1 && version != 2)
                        || (version == 1 && count != 1)
                        || (version == 2 && (count > 2
                        || (count == 2 && previous != 1)))) {
                    throw new SQLException(
                            "replacement_schema_history_mismatch"
                    );
                }
                previous = version;
            }
        }
        if (count == 0 || count > 2 || previous != 2) {
            throw new SQLException("replacement_schema_history_mismatch");
        }
    }

    private static Map<String, String> expectedSchemaObjects(String script)
            throws Exception {
        try (Connection authority = DriverManager.getConnection(
                "jdbc:sqlite::memory:"
        )) {
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

    private static String loadScript(String resource) throws Exception {
        try (InputStream stream =
                     SqliteSchemaV2ReadOnlyGateway.class
                             .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new SQLException(
                        "replacement_schema_resource_missing"
                );
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
        String normalized = sql.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        return normalized.endsWith(";")
                ? normalized.substring(0, normalized.length() - 1).trim()
                : normalized;
    }
}
