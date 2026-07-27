package com.alechilles.alecstamework.persistence.bonded;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;

/** Verifies schema objects that enforce bonded domain authority. */
final class BondedCompanionSchemaAuthorityVerifier {
    private static final String CAPTURE_SOURCE_INDEX =
            "bonded_capture_source_once_idx";

    static boolean hasCaptureSourceFence(Connection connection) throws Exception {
        Objects.requireNonNull(connection, "connection");
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT indexes."unique", indexes.partial, schema.sql
                     FROM pragma_index_list(
                         'bonded_companion_operation'
                     ) indexes
                     JOIN sqlite_master schema
                       ON schema.type = 'index'
                      AND schema.name = indexes.name
                     WHERE indexes.name = 'bonded_capture_source_once_idx'
                     """)) {
            if (!row.next() || row.getInt(1) != 1 || row.getInt(2) != 1) {
                return false;
            }
            String sql = row.getString(3);
            return sql != null && matchesCaptureFence(sql) && !row.next();
        }
    }

    private static boolean matchesCaptureFence(String sql) {
        String normalized = sql.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return normalized.contains("create unique index "
                + CAPTURE_SOURCE_INDEX)
                && normalized.contains(
                "$.captureevidence.sourcenpcuuid")
                && normalized.contains("operation_type = 'capture'")
                && normalized.contains("operation_state = 'succeeded'");
    }
}
