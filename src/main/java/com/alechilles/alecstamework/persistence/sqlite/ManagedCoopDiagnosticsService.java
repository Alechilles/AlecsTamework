package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds one read-only, point-in-time audit of durable and indexed managed-coop state. */
public final class ManagedCoopDiagnosticsService {
    private final SqliteConnectionManager connections;
    private final ManagedCoopRuntimeServices runtimeServices;

    ManagedCoopDiagnosticsService(@Nonnull SqliteConnectionManager connections,
                                  @Nonnull ManagedCoopRuntimeServices runtimeServices) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.runtimeServices = Objects.requireNonNull(runtimeServices, "runtimeServices");
    }

    /** Returns a complete audit or an explicit failure; partial reads never masquerade as clean. */
    @Nonnull
    public AuditReport inspect() {
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            IndexEpoch before = indexEpoch();
            Map<String, Long> authorities = grouped(connection, """
                    SELECT authority_state, COUNT(*) FROM managed_coop_authority
                    WHERE active = 1 GROUP BY authority_state ORDER BY authority_state
                    """);
            Map<String, Long> residents = grouped(connection, """
                    SELECT state, COUNT(*) FROM managed_coop_residents
                    WHERE active = 1 GROUP BY state ORDER BY state
                    """);
            Map<String, Long> operations = grouped(connection, """
                    SELECT operation_kind || ':' || state, COUNT(*)
                    FROM coop_lifecycle_operations WHERE active = 1
                    GROUP BY operation_kind, state ORDER BY operation_kind, state
                    """);
            long activeImportSessions = scalar(connection,
                    "SELECT COUNT(*) FROM managed_coop_import_sessions WHERE active = 1");
            long pendingImportSources = scalar(connection, """
                    SELECT COUNT(*) FROM managed_coop_import_sources
                    WHERE disposition_kind IS NULL
                    """);
            long awaitingAbsenceProof = scalar(connection, """
                    SELECT COUNT(*) FROM managed_coop_import_sources
                    WHERE disposition_kind IN ('MATCHED', 'IMPORTED')
                      AND neutralization_state = 'AUTHORIZED'
                    """);
            long unresolvedImportConflicts = scalar(connection, """
                    SELECT COUNT(*) FROM coop_import_conflicts
                    WHERE resolution_state = 'UNRESOLVED'
                    """);
            IndexEpoch after = indexEpoch();
            connection.rollback();
            boolean coherentTrustedEpoch = before.equals(after) && after.trusted();
            return new AuditReport(
                    ReportStatus.COMPLETE,
                    authorities,
                    residents,
                    operations,
                    activeImportSessions,
                    pendingImportSources,
                    awaitingAbsenceProof,
                    unresolvedImportConflicts,
                    coherentTrustedEpoch,
                    after.residentRevision(),
                    after.operationRevision(),
                    null,
                    null
            );
        } catch (SQLException | RuntimeException exception) {
            return new AuditReport(
                    ReportStatus.FAILED,
                    Map.of(), Map.of(), Map.of(),
                    0L, 0L, 0L, 0L,
                    false,
                    runtimeServices.residentIndex().snapshot().revision(),
                    runtimeServices.lifecycleIndex().snapshot().revision(),
                    failureDetail(exception),
                    exception
            );
        }
    }

    @Nonnull
    private IndexEpoch indexEpoch() {
        return new IndexEpoch(
                runtimeServices.compositeIndexRefreshService().isTrusted(),
                runtimeServices.residentIndex().snapshot().revision(),
                runtimeServices.lifecycleIndex().snapshot().revision());
    }

    @Nonnull
    private Map<String, Long> grouped(Connection connection, String sql) throws SQLException {
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                counts.put(rows.getString(1), rows.getLong(2));
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    private long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new SQLException("managed_coop_diagnostic_count_missing");
            }
            return rows.getLong(1);
        }
    }

    @Nonnull
    private String failureDetail(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    public enum ReportStatus {
        COMPLETE,
        FAILED
    }

    /** One coherent in-memory index epoch sampled around the SQLite read transaction. */
    private record IndexEpoch(boolean trusted, long residentRevision, long operationRevision) {
    }

    /** Immutable values safe to format after the SQLite connection closes. */
    public record AuditReport(
            @Nonnull ReportStatus status,
            @Nonnull Map<String, Long> activeAuthoritiesByState,
            @Nonnull Map<String, Long> activeResidentsByState,
            @Nonnull Map<String, Long> activeOperationsByKindAndState,
            long activeImportSessions,
            long pendingImportSources,
            long awaitingAbsenceProof,
            long unresolvedImportConflicts,
            boolean compositeIndexTrusted,
            long residentIndexRevision,
            long operationIndexRevision,
            @Nullable String failureReason,
            @Nullable Throwable failure) {
        public AuditReport {
            Objects.requireNonNull(status, "status");
            activeAuthoritiesByState = orderedCopy(activeAuthoritiesByState);
            activeResidentsByState = orderedCopy(activeResidentsByState);
            activeOperationsByKindAndState = orderedCopy(activeOperationsByKindAndState);
        }

        public long activeAuthorities() {
            return total(activeAuthoritiesByState);
        }

        public long activeResidents() {
            return total(activeResidentsByState);
        }

        public long activeOperations() {
            return total(activeOperationsByKindAndState);
        }

        public boolean requiresAttention() {
            return status == ReportStatus.FAILED
                    || !compositeIndexTrusted
                    || activeImportSessions > 0L
                    || pendingImportSources > 0L
                    || awaitingAbsenceProof > 0L
                    || unresolvedImportConflicts > 0L;
        }

        private static long total(Map<String, Long> counts) {
            long result = 0L;
            for (long count : counts.values()) {
                result += count;
            }
            return result;
        }

        private static Map<String, Long> orderedCopy(Map<String, Long> source) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }
}
