package com.alechilles.alecstamework.persistence.diagnostics;

import com.alechilles.alecstamework.persistence.incidents.PersistenceScopeFactory;
import com.alechilles.alecstamework.persistence.sqlite.SqliteConnectionManager;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

/** Reads bounded, identity-redacted diagnostic sections from one coherent SQLite snapshot. */
final class PersistenceDiagnosticDatabaseSnapshotReader {
    private static final int ROW_LIMIT = 100;
    private final SqliteConnectionManager connections;
    private final PersistenceScopeFactory scopes;

    PersistenceDiagnosticDatabaseSnapshotReader(SqliteConnectionManager connections,
                                                PersistenceScopeFactory scopes) {
        this.connections = connections;
        this.scopes = scopes;
    }

    @Nonnull
    Snapshot read() throws Exception {
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only=ON");
                statement.execute("PRAGMA busy_timeout=1000");
            }
            Snapshot snapshot = new Snapshot(
                    incidents(connection), quarantines(connection), operations(connection),
                    integrity(connection), reconciliation(connection));
            connection.commit();
            return snapshot;
        }
    }

    static Snapshot unavailableSnapshot(String error) {
        Section<Object> unavailable = Section.unavailable(error);
        return new Snapshot(cast(unavailable), cast(unavailable), cast(unavailable),
                cast(unavailable), cast(unavailable));
    }

    @SuppressWarnings("unchecked")
    private static <T> Section<T> cast(Section<?> section) {
        return (Section<T>) section;
    }

    private Section<IncidentRow> incidents(Connection connection) {
        String sql = """
                SELECT incident_id, status, severity, failure_class, disposition, domain, phase,
                       reason_code, operation_id, opened_at_ms, last_seen_at_ms,
                       occurrence_count, recovery_attempts, resolution_code, telemetry_correlation_id
                FROM persistence_incidents ORDER BY last_seen_at_ms DESC LIMIT 101
                """;
        return query(connection, sql, rows -> new IncidentRow(
                rows.getString("incident_id"), rows.getString("status"), rows.getString("severity"),
                rows.getString("failure_class"), rows.getString("disposition"), rows.getString("domain"),
                rows.getString("phase"), rows.getString("reason_code"), rows.getString("operation_id"),
                rows.getLong("opened_at_ms"), rows.getLong("last_seen_at_ms"),
                rows.getLong("occurrence_count"), rows.getLong("recovery_attempts"),
                rows.getString("resolution_code"), rows.getString("telemetry_correlation_id")));
    }

    private Section<QuarantineRow> quarantines(Connection connection) {
        String sql = """
                SELECT q.quarantine_id, q.incident_id, q.scope_type, s.scope_hash,
                       s.authority_dimension, q.domain, q.reason_code, q.state,
                       q.evidence_hash, q.generation, q.created_at_ms, q.updated_at_ms,
                       q.cleared_at_ms, q.clear_verifier
                FROM persistence_quarantines q
                LEFT JOIN persistence_incident_scopes s
                  ON s.incident_id = q.incident_id
                 AND s.scope_type = q.scope_type
                 AND s.scope_key = q.scope_key
                ORDER BY CASE q.state WHEN 'ACTIVE' THEN 0 WHEN 'VERIFYING' THEN 1 ELSE 2 END,
                         q.updated_at_ms DESC LIMIT 101
                """;
        return query(connection, sql, rows -> new QuarantineRow(
                rows.getString("quarantine_id"), rows.getString("incident_id"),
                rows.getString("scope_type"), rows.getString("scope_hash"),
                rows.getString("authority_dimension"), rows.getString("domain"),
                rows.getString("reason_code"), rows.getString("state"),
                rows.getString("evidence_hash"), rows.getLong("generation"),
                rows.getLong("created_at_ms"), rows.getLong("updated_at_ms"),
                rows.getLong("cleared_at_ms"), rows.getString("clear_verifier")));
    }

    private Section<OperationRow> operations(Connection connection) {
        ArrayList<OperationRow> rows = new ArrayList<>();
        try {
            readPopulationOperations(connection, rows);
            readCoopOperations(connection, rows);
            readRecoveryOperations(connection, rows);
            readCaptureAttemptOperations(connection, rows);
            readBondedVesselOperations(connection, rows);
            readPopulationGroupOperations(connection, rows);
            readProvisioningOperations(connection, rows);
            rows.sort(java.util.Comparator.comparingLong(OperationRow::updatedAtMs).reversed());
            int omitted = Math.max(0, rows.size() - ROW_LIMIT);
            if (rows.size() > ROW_LIMIT) rows.subList(ROW_LIMIT, rows.size()).clear();
            return Section.complete(rows, omitted);
        } catch (Exception failure) {
            return Section.unavailable(errorCode(failure));
        }
    }

    private void readPopulationOperations(Connection connection, List<OperationRow> out) throws Exception {
        String sql = """
                SELECT operation_id, profile_id, operation_type, state, created_at_ms,
                       updated_at_ms, completed_at_ms, target_context_json IS NOT NULL AS has_source
                FROM companion_population_operations
                ORDER BY CASE state WHEN 'PREPARED' THEN 0 WHEN 'APPLYING' THEN 0
                         WHEN 'APPLIED' THEN 0 WHEN 'COMPENSATING' THEN 0 ELSE 1 END,
                         updated_at_ms DESC LIMIT 100
                """;
        readOperations(connection, sql, "population", out);
    }

    private void readCoopOperations(Connection connection, List<OperationRow> out) throws Exception {
        String sql = """
                SELECT operation_id, profile_id, operation_kind AS operation_type, state,
                       created_at_ms, updated_at_ms, completed_at_ms,
                       source_npc_uuid IS NOT NULL AS has_source
                FROM coop_lifecycle_operations WHERE active = 1
                ORDER BY updated_at_ms DESC LIMIT 100
                """;
        readOperations(connection, sql, "managed_coop", out);
    }

    private void readRecoveryOperations(Connection connection, List<OperationRow> out) throws Exception {
        String sql = """
                SELECT operation_id, profile_id, 'recovery' AS operation_type, state,
                       created_at_ms, updated_at_ms, completed_at_ms,
                       source_npc_uuid IS NOT NULL AS has_source
                FROM npc_recovery_operations WHERE active = 1
                ORDER BY updated_at_ms DESC LIMIT 100
                """;
        readOperations(connection, sql, "lifecycle_recovery", out);
    }

    private void readCaptureAttemptOperations(Connection connection, List<OperationRow> out) throws Exception {
        String sql = """
                SELECT attempt_id AS operation_id, profile_id, 'capture_attempt' AS operation_type,
                       state, created_at_ms, updated_at_ms, completed_at_ms,
                       source_context_json IS NOT NULL AS has_source
                FROM capture_attempts
                ORDER BY CASE state WHEN 'PREPARED' THEN 0 WHEN 'RESOLVED_SUCCESS' THEN 0
                         WHEN 'APPLYING' THEN 0 WHEN 'COMPENSATING' THEN 0
                         WHEN 'QUARANTINED' THEN 0 ELSE 1 END,
                         updated_at_ms DESC LIMIT 100
                """;
        readOperations(connection, sql, "capture_policy", out);
    }

    private void readBondedVesselOperations(Connection connection, List<OperationRow> out) throws Exception {
        String sql = """
                SELECT operation_id, profile_id, action AS operation_type, state,
                       created_at_ms, updated_at_ms, completed_at_ms,
                       source_context_json IS NOT NULL AS has_source
                FROM bonded_vessel_operations
                ORDER BY CASE state WHEN 'PREPARED' THEN 0 WHEN 'APPLYING' THEN 0
                         WHEN 'APPLIED' THEN 0 WHEN 'COMPENSATING' THEN 0
                         WHEN 'QUARANTINED' THEN 0 ELSE 1 END,
                         updated_at_ms DESC LIMIT 100
                """;
        readOperations(connection, sql, "bonded_vessel", out);
    }

    private void readPopulationGroupOperations(Connection connection, List<OperationRow> out) throws Exception {
        String sql = """
                SELECT operation_id, profile_id, operation_type, state,
                       created_at_ms, updated_at_ms, completed_at_ms,
                       (old_owner_uuid IS NOT NULL OR new_owner_uuid IS NOT NULL) AS has_source
                FROM companion_population_group_operations
                ORDER BY CASE state WHEN 'PREPARED' THEN 0 WHEN 'APPLYING' THEN 0
                         WHEN 'APPLIED' THEN 0 WHEN 'COMPENSATING' THEN 0
                         WHEN 'QUARANTINED' THEN 0 ELSE 1 END,
                         updated_at_ms DESC LIMIT 100
                """;
        readOperations(connection, sql, "population_group", out);
    }

    private void readProvisioningOperations(Connection connection, List<OperationRow> out) throws Exception {
        String sql = """
                SELECT operation_id,
                       COALESCE(canonical_profile_id, provisional_profile_id) AS profile_id,
                       requested_disposition AS operation_type, state,
                       created_at_ms, updated_at_ms, completed_at_ms,
                       destination_context_json IS NOT NULL AS has_source
                FROM companion_provisioning_operations
                ORDER BY CASE state WHEN 'PREPARING_DORMANT' THEN 0 WHEN 'DORMANT_PREPARED' THEN 0
                         WHEN 'DORMANT_APPLYING' THEN 0 WHEN 'DORMANT_COMMITTED' THEN 0
                         WHEN 'ACTIVE_PREPARED' THEN 0 WHEN 'ACTIVE_APPLYING' THEN 0
                         WHEN 'PARTIAL_DORMANT' THEN 0 WHEN 'QUARANTINED' THEN 0 ELSE 1 END,
                         updated_at_ms DESC LIMIT 100
                """;
        readOperations(connection, sql, "companion_provisioning", out);
    }

    private void readOperations(Connection connection, String sql, String domain,
                                List<OperationRow> out) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                out.add(new OperationRow(
                        domain, rows.getString("operation_id"), rows.getString("operation_type"),
                        rows.getString("state"), scopes.profile(rows.getString("profile_id")).scopeHash(),
                        rows.getBoolean("has_source"), rows.getLong("created_at_ms"),
                        rows.getLong("updated_at_ms"), rows.getLong("completed_at_ms")));
            }
        }
    }

    private Section<IntegrityIssue> integrity(Connection connection) {
        ArrayList<IntegrityIssue> issues = new ArrayList<>();
        try {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("PRAGMA integrity_check")) {
                while (rows.next()) {
                    String detail = rows.getString(1);
                    if (!"ok".equalsIgnoreCase(detail)) {
                        issues.add(new IntegrityIssue("sqlite_integrity", bounded(detail), 1L));
                    }
                }
            }
            long foreignKeys = countRows(connection, "PRAGMA foreign_key_check");
            if (foreignKeys > 0L) issues.add(new IntegrityIssue("foreign_key_violation", null, foreignKeys));
            duplicateCheck(connection, issues, "duplicate_current_profile_uuid", """
                    SELECT COUNT(*) FROM (SELECT current_npc_uuid FROM npc_profiles
                    WHERE current_npc_uuid IS NOT NULL GROUP BY current_npc_uuid HAVING COUNT(*) > 1)
                    """);
            duplicateCheck(connection, issues, "duplicate_active_population_profile", """
                    SELECT COUNT(*) FROM (SELECT profile_id FROM companion_population_operations
                    WHERE state IN ('PREPARED','APPLYING','APPLIED','COMPENSATING')
                    GROUP BY profile_id HAVING COUNT(*) > 1)
                    """);
            duplicateCheck(connection, issues, "duplicate_active_vessel_profile", """
                    SELECT COUNT(*) FROM (SELECT profile_id FROM bonded_vessel_bindings
                    WHERE lifecycle_state <> 'RELEASED'
                    GROUP BY profile_id HAVING COUNT(*) > 1)
                    """);
            duplicateCheck(connection, issues, "duplicate_nonterminal_vessel_generation", """
                    SELECT COUNT(*) FROM (SELECT binding_id, prior_generation FROM bonded_vessel_operations
                    WHERE state IN ('PREPARED','APPLYING','APPLIED','COMPENSATING','QUARANTINED')
                    GROUP BY binding_id, prior_generation HAVING COUNT(*) > 1)
                    """);
            duplicateCheck(connection, issues, "duplicate_nonterminal_population_group_profile", """
                    SELECT COUNT(*) FROM (SELECT profile_id FROM companion_population_group_operations
                    WHERE state IN ('PREPARED','APPLYING','APPLIED','COMPENSATING','QUARANTINED')
                    GROUP BY profile_id HAVING COUNT(*) > 1)
                    """);
            duplicateCheck(connection, issues, "duplicate_capture_origin", """
                    SELECT COUNT(*) FROM (SELECT caller_namespace, idempotency_key FROM capture_attempts
                    WHERE caller_namespace IS NOT NULL
                    GROUP BY caller_namespace, idempotency_key HAVING COUNT(*) > 1)
                    """);
            duplicateCheck(connection, issues, "duplicate_provisioning_origin", """
                    SELECT COUNT(*) FROM (SELECT caller_namespace, idempotency_key
                    FROM companion_provisioning_operations
                    GROUP BY caller_namespace, idempotency_key HAVING COUNT(*) > 1)
                    """);
            return Section.complete(issues, 0);
        } catch (Exception failure) {
            return Section.unavailable(errorCode(failure));
        }
    }

    private Section<ReconciliationRow> reconciliation(Connection connection) {
        String sql = """
                SELECT coverage_dimension, state, COUNT(*) AS row_count,
                       SUM(scanned_count) AS scanned_count,
                       SUM(CASE WHEN estimated_total >= 0 THEN estimated_total ELSE 0 END) AS estimated_total,
                       MAX(updated_at_ms) AS updated_at_ms,
                       SUM(CASE WHEN last_error IS NULL THEN 0 ELSE 1 END) AS error_count
                FROM companion_population_reconciliation
                GROUP BY coverage_dimension, state
                ORDER BY coverage_dimension, state LIMIT 101
                """;
        return query(connection, sql, rows -> new ReconciliationRow(
                rows.getString("coverage_dimension"), rows.getString("state"),
                rows.getLong("row_count"), rows.getLong("scanned_count"),
                rows.getLong("estimated_total"), rows.getLong("updated_at_ms"),
                rows.getLong("error_count")));
    }

    private <T> Section<T> query(Connection connection, String sql, RowMapper<T> mapper) {
        ArrayList<T> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) values.add(mapper.map(rows));
            int omitted = Math.max(0, values.size() - ROW_LIMIT);
            if (values.size() > ROW_LIMIT) values.removeLast();
            return Section.complete(values, omitted);
        } catch (Exception failure) {
            return Section.unavailable(errorCode(failure));
        }
    }

    private long countRows(Connection connection, String sql) throws Exception {
        long count = 0L;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) count++;
        }
        return count;
    }

    private void duplicateCheck(Connection connection, List<IntegrityIssue> issues,
                                String id, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            long groups = rows.next() ? rows.getLong(1) : 0L;
            if (groups > 0L) issues.add(new IntegrityIssue(id, null, groups));
        }
    }

    private String errorCode(Exception failure) {
        return "read_failed:" + failure.getClass().getSimpleName();
    }

    private String bounded(String value) {
        if (value == null || value.isBlank()) return null;
        return value.substring(0, Math.min(160, value.length()));
    }

    record Snapshot(Section<IncidentRow> incidents, Section<QuarantineRow> quarantines,
                    Section<OperationRow> operations, Section<IntegrityIssue> integrity,
                    Section<ReconciliationRow> reconciliation) {
    }

    record Section<T>(String status, String error, int omittedCount, List<T> rows) {
        static <T> Section<T> complete(List<T> rows, int omitted) {
            return new Section<>("complete", null, omitted, List.copyOf(rows));
        }

        static <T> Section<T> unavailable(String error) {
            return new Section<>("unavailable", error, 0, List.of());
        }
    }

    record IncidentRow(String incidentId, String status, String severity, String failureClass,
                       String disposition, String domain, String phase, String reasonCode,
                       String operationId, long openedAtMs, long lastSeenAtMs,
                       long occurrenceCount, long recoveryAttempts, String resolutionCode,
                       String telemetryCorrelationId) {
    }

    record QuarantineRow(String quarantineId, String incidentId, String scopeType, String scopeHash,
                         String authorityDimension, String domain, String reasonCode, String state,
                         String evidenceHash, long generation, long createdAtMs, long updatedAtMs,
                         long clearedAtMs, String clearVerifier) {
    }

    record OperationRow(String domain, String operationId, String operationType, String state,
                        String profileHash, boolean sourceRetained, long createdAtMs,
                        long updatedAtMs, long completedAtMs) {
    }

    record IntegrityIssue(String id, String detail, long affectedGroups) {
    }

    record ReconciliationRow(String dimension, String state, long rowCount, long scannedCount,
                             long estimatedTotal, long updatedAtMs, long errorCount) {
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rows) throws Exception;
    }
}
