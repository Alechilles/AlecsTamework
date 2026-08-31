package com.alechilles.alecstamework.persistence.adapter.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Verifies the routed-read schema version shipped before Tamework 3.3.0. */
public final class SqliteReleasedRoutedV2Gateway {
    public static final String SCHEMA_HASH =
            "b72b00e5e77277f936866aa2f20555c3d35473379e40b6608890b9f0a382d5d7";
    private static final SqliteSchemaDefinitionCatalog.SchemaObject ROUTED_INDEX =
            new SqliteSchemaDefinitionCatalog.SchemaObject(
                    "index", SqliteSchemaV2Manager.ROUTED_READ_INDEX
            );
    private static final SqliteSchemaDefinitionCatalog.SchemaObject HISTORY =
            new SqliteSchemaDefinitionCatalog.SchemaObject(
                    "table", "schema_history"
            );
    private static final Set<SqliteSchemaDefinitionCatalog.SchemaObject> OBJECTS =
            releasedObjects();
    private static final Map<SqliteSchemaDefinitionCatalog.SchemaObject, String>
            DEFINITIONS = releasedDefinitions();

    private SqliteReleasedRoutedV2Gateway() {
    }

    /** Verifies exact compatibility before any migration writes are allowed. */
    public static void verify(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        SqliteSchemaDefinitionCatalog.Inspection inspection =
                SqliteSchemaDefinitionCatalog.inspect(connection);
        if (!inspection.objects().equals(OBJECTS)
                || !inspection.definitions().equals(DEFINITIONS)) {
            throw new SQLException("replacement_released_v2_schema_mismatch");
        }
        verifyHistory(connection);
        verifyIntegrity(connection);
    }

    /** Verifies all table and index contents plus every foreign-key edge. */
    public static void verifyIntegrity(Connection connection)
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

    static boolean matches(
            SqliteSchemaDefinitionCatalog.Inspection inspection
    ) {
        return inspection.objects().equals(OBJECTS)
                && inspection.definitions().equals(DEFINITIONS);
    }

    private static void verifyHistory(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT version, lineage, applied_at_ms, schema_hash
                     FROM schema_history
                     """)) {
            if (!rows.next()
                    || rows.getInt("version") != SqliteSchemaV2Manager.VERSION
                    || !SqliteSchemaV2Manager.LINEAGE.equals(
                    rows.getString("lineage"))
                    || rows.getObject("applied_at_ms") == null
                    || !SCHEMA_HASH.equals(rows.getString("schema_hash"))
                    || rows.next()) {
                throw new SQLException(
                        "replacement_released_v2_history_mismatch"
                );
            }
        }
    }

    private static Set<SqliteSchemaDefinitionCatalog.SchemaObject>
    releasedObjects() {
        HashSet<SqliteSchemaDefinitionCatalog.SchemaObject> objects =
                new HashSet<>();
        for (SqliteSchemaInspector.SchemaObject object
                : SqliteSchemaV1Manager.requiredSchemaObjects()) {
            objects.add(new SqliteSchemaDefinitionCatalog.SchemaObject(
                    object.type(), object.name()
            ));
        }
        objects.add(ROUTED_INDEX);
        return Set.copyOf(objects);
    }

    private static Map<SqliteSchemaDefinitionCatalog.SchemaObject, String>
    releasedDefinitions() {
        HashMap<SqliteSchemaDefinitionCatalog.SchemaObject, String> definitions =
                new HashMap<>();
        for (Map.Entry<SqliteSchemaInspector.SchemaObject, String> entry
                : SqliteSchemaV1Manager.requiredSchemaDefinitions().entrySet()) {
            definitions.put(
                    new SqliteSchemaDefinitionCatalog.SchemaObject(
                            entry.getKey().type(), entry.getKey().name()
                    ),
                    entry.getValue()
            );
        }
        definitions.put(
                HISTORY,
                definitions.get(HISTORY).replace(
                        "check (version = 1)",
                        "check (version in (1, 2))"
                )
        );
        definitions.put(
                ROUTED_INDEX,
                SqliteSchemaDefinitionCatalog.normalize("""
                        CREATE INDEX idx_projection_outbox_type_sequence
                            ON projection_outbox(event_type, event_sequence)
                        """)
        );
        return Map.copyOf(definitions);
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
