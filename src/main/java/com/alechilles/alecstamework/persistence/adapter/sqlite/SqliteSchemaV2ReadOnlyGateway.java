package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
            if (!SqliteSchemaDefinitionCatalog
                    .inspectReadOnlyCompatibility(connection).equals(
                    SqliteSchemaDefinitionCatalog.expectedReadOnlySchema(
                            v2Script
                    )
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
            SqliteSchemaVerificationChecks.requireSingleValue(
                    connection,
                    "PRAGMA quick_check(1)",
                    "ok",
                    "replacement_integrity_check_failed"
            );
            SqliteSchemaVerificationChecks.requireNoRows(
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

}
