package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.persistence.adapter.sqlite
        .SqliteBondedCompanionProfileRow;
import com.google.gson.Gson;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Verifies exact schema objects that enforce capture-source authority. */
final class BondedCompanionSchemaAuthorityVerifier {
    private static final Gson GSON = new Gson();
    private static final String LEGACY_INDEX =
            "bonded_capture_source_once_idx";
    private static final String DURABLE_INDEX =
            "bonded_capture_source_uuid_uq";
    private static final String LEGACY_SQL = """
            CREATE UNIQUE INDEX bonded_capture_source_once_idx
            ON bonded_companion_operation(
                json_extract(result_json, '$.captureEvidence.sourceNpcUuid')
            )
            WHERE operation_type = 'CAPTURE'
              AND operation_state = 'SUCCEEDED'
              AND json_type(
                  result_json, '$.captureEvidence.sourceNpcUuid'
              ) = 'text'
            """;

    static boolean hasLegacyCaptureSourceFence(Connection connection)
            throws Exception {
        Objects.requireNonNull(connection, "connection");
        Index index = index(connection, "bonded_companion_operation",
                LEGACY_INDEX);
        return index != null && index.unique && index.partial
                && normalize(LEGACY_SQL).equals(normalize(index.sql));
    }

    static boolean hasDurableCaptureSourceFence(Connection connection)
            throws Exception {
        Objects.requireNonNull(connection, "connection");
        Index index = index(connection, "bonded_companion_capture_source",
                DURABLE_INDEX);
        return index != null && index.unique && !index.partial
                && exactColumns(connection, DURABLE_INDEX,
                List.of("source_npc_uuid"))
                && hasProfileCascade(connection)
                && hasCallerKeyUniqueness(connection)
                && captureRowsDeserializable(connection)
                && captureRowsConsistent(connection)
                && retainedOperationsClaimed(connection);
    }

    private static Index index(
            Connection connection, String table, String name
    ) throws Exception {
        String escaped = table.replace("'", "''");
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT indexes."unique", indexes.partial, schema.sql
                     FROM pragma_index_list('%s') indexes
                     JOIN sqlite_master schema
                       ON schema.type = 'index' AND schema.name = indexes.name
                     WHERE indexes.name = '%s'
                     """.formatted(escaped, name))) {
            if (!row.next()) return null;
            Index index = new Index(
                    row.getInt(1) == 1, row.getInt(2) == 1,
                    row.getString(3));
            return row.next() ? null : index;
        }
    }

    private static boolean exactColumns(
            Connection connection, String index, List<String> expected
    ) throws Exception {
        ArrayList<String> keys = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "PRAGMA index_xinfo('" + index + "')")) {
            while (rows.next()) {
                if (rows.getInt("key") != 1) continue;
                if (rows.getInt("cid") < 0 || rows.getString("name") == null) {
                    return false;
                }
                keys.add(rows.getString("name"));
            }
        }
        return keys.equals(expected);
    }

    private static boolean hasProfileCascade(Connection connection)
            throws Exception {
        ArrayList<String> from = new ArrayList<>();
        ArrayList<String> to = new ArrayList<>();
        Integer id = null;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "PRAGMA foreign_key_list('bonded_companion_capture_source')")) {
            while (rows.next()) {
                if (!"bonded_companion_profile".equals(rows.getString("table"))
                        || !"CASCADE".equalsIgnoreCase(
                        rows.getString("on_delete"))) continue;
                if (id == null) id = rows.getInt("id");
                if (id != rows.getInt("id")) return false;
                from.add(rows.getString("from"));
                to.add(rows.getString("to"));
            }
        }
        return from.equals(List.of("profile_id", "owner_uuid", "roster_id"))
                && to.equals(List.of(
                "profile_id", "owner_uuid", "roster_id"));
    }

    private static boolean hasCallerKeyUniqueness(Connection connection)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT name FROM pragma_index_list(
                         'bonded_companion_capture_source'
                     ) WHERE "unique" = 1 AND partial = 0
                     """)) {
            while (rows.next()) {
                if (exactColumns(connection, rows.getString(1), List.of(
                        "caller_namespace", "idempotency_key"))) return true;
            }
            return false;
        }
    }

    private static boolean captureRowsConsistent(Connection connection)
            throws Exception {
        return absent(connection, """
                SELECT 1 FROM bonded_companion_capture_source source
                WHERE source.profile_id IS NOT json_extract(
                          source.capture_evidence_json, '$.profileId')
                   OR source.owner_uuid IS NOT json_extract(
                          source.capture_evidence_json, '$.ownerUuid')
                   OR source.roster_id IS NOT json_extract(
                          source.capture_evidence_json, '$.rosterId')
                   OR source.source_npc_uuid IS NOT json_extract(
                          source.capture_evidence_json, '$.sourceNpcUuid')
                   OR source.source_world_key IS NOT json_extract(
                          source.capture_evidence_json, '$.sourceWorldKey')
                   OR source.caller_namespace IS NOT json_extract(
                          source.capture_evidence_json, '$.callerNamespace')
                   OR source.idempotency_key IS NOT json_extract(
                          source.capture_evidence_json, '$.idempotencyKey')
                   OR source.committed_at_ms IS NOT json_extract(
                          source.capture_evidence_json, '$.committedAtMs')
                   OR json_extract(
                          source.capture_evidence_json, '$.outcome'
                      ) IS NOT 'CAPTURED'
                   OR json_extract(
                          source.capture_evidence_json, '$.successDisposition'
                      ) IS NOT 'STORE_BONDED_COMPANION'
                   OR source.profile_id IS NOT json_extract(
                          source.capture_snapshot_json, '$.profileId')
                   OR source.owner_uuid IS NOT json_extract(
                          source.capture_snapshot_json, '$.ownerUuid')
                   OR source.roster_id IS NOT json_extract(
                          source.capture_snapshot_json, '$.rosterId')
                   OR json_extract(
                          source.capture_evidence_json, '$.familyId'
                      ) IS NOT json_extract(
                          source.capture_snapshot_json, '$.familyId')
                   OR json_extract(
                          source.capture_evidence_json, '$.roleId'
                      ) IS NOT json_extract(
                          source.capture_snapshot_json, '$.roleId')
                   OR json_extract(
                          source.capture_snapshot_json, '$.state'
                      ) IS NOT 'STORED'
                   OR json_extract(
                          source.capture_snapshot_json, '$.revision'
                      ) IS NOT 0
                LIMIT 1
                """);
    }

    private static boolean captureRowsDeserializable(Connection connection)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT capture_evidence_json, capture_snapshot_json
                     FROM bonded_companion_capture_source
                     """)) {
            while (rows.next()) {
                try {
                    BondedCompanionCaptureEvidence evidence = GSON.fromJson(
                            rows.getString(1),
                            BondedCompanionCaptureEvidence.class);
                    SqliteBondedCompanionProfileRow profile = GSON.fromJson(
                            rows.getString(2),
                            SqliteBondedCompanionProfileRow.class);
                    if (evidence == null || profile == null) return false;
                } catch (RuntimeException failure) {
                    return false;
                }
            }
            return true;
        }
    }

    private static boolean retainedOperationsClaimed(Connection connection)
            throws Exception {
        return absent(connection, """
                SELECT 1 FROM bonded_companion_operation operation
                WHERE operation.operation_type = 'CAPTURE'
                  AND operation.operation_state = 'SUCCEEDED'
                  AND EXISTS (
                      SELECT 1 FROM bonded_companion_profile profile
                      WHERE profile.profile_id = operation.profile_id
                        AND profile.owner_uuid = operation.owner_uuid
                        AND profile.roster_id = operation.roster_id
                  )
                  AND (
                      json_type(
                          operation.result_json, '$.captureEvidence'
                      ) IS NOT 'object'
                      OR NOT EXISTS (
                          SELECT 1
                          FROM bonded_companion_capture_source source
                          WHERE source.caller_namespace =
                                    operation.caller_namespace
                            AND source.idempotency_key =
                                    operation.idempotency_key
                            AND source.request_hash = operation.request_hash
                            AND source.profile_id = operation.profile_id
                            AND source.owner_uuid = operation.owner_uuid
                            AND source.roster_id = operation.roster_id
                            AND source.source_npc_uuid = json_extract(
                                operation.result_json,
                                '$.captureEvidence.sourceNpcUuid')
                            AND source.source_world_key = json_extract(
                                operation.result_json,
                                '$.captureEvidence.sourceWorldKey')
                      )
                  )
                LIMIT 1
                """);
    }

    private static boolean absent(Connection connection, String sql)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            return !row.next();
        }
    }

    private static String normalize(String sql) {
        return sql == null ? "" : sql.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll(";+$", "");
    }

    private record Index(boolean unique, boolean partial, String sql) {
    }
}
