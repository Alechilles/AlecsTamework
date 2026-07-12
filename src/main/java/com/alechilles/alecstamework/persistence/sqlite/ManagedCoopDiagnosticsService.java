package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.ManagedCoopLifecycleOperationIndex;
import com.alechilles.alecstamework.items.ManagedCoopResidentIndex;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Builds one read-only, point-in-time audit of durable and indexed managed-coop state. */
public final class ManagedCoopDiagnosticsService {
    private static final int MAX_DETAIL_ROWS = 25;

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
            List<ResidentDetail> residentDetails = residentDetails(connection);
            List<OperationDetail> operationDetails = operationDetails(connection);
            IndexEpoch after = indexEpoch();
            connection.rollback();
            boolean coherentTrustedEpoch = before.equals(after)
                    && after.trusted()
                    && after.authorityCount() == total(authorities)
                    && after.residentCount() == total(residents)
                    && after.operationCount() == total(operations);
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
                    residentDetails,
                    operationDetails,
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
                    List.of(),
                    List.of(),
                    failureDetail(exception),
                    exception
            );
        }
    }

    @Nonnull
    private IndexEpoch indexEpoch() {
        for (int attempt = 0; attempt < 3; attempt++) {
            ManagedCoopResidentIndex.Snapshot residents =
                    runtimeServices.residentIndex().snapshot();
            ManagedCoopLifecycleOperationIndex.Snapshot operations =
                    runtimeServices.lifecycleIndex().snapshot();
            boolean trusted = runtimeServices.compositeIndexRefreshService().isTrusted();
            if (residents == runtimeServices.residentIndex().snapshot()
                    && operations == runtimeServices.lifecycleIndex().snapshot()) {
                return new IndexEpoch(
                        trusted,
                        residents.revision(),
                        operations.revision(),
                        residents.authorities().size(),
                        residents.allResidents().size(),
                        operations.operations().size());
            }
        }
        ManagedCoopResidentIndex.Snapshot residents =
                runtimeServices.residentIndex().snapshot();
        ManagedCoopLifecycleOperationIndex.Snapshot operations =
                runtimeServices.lifecycleIndex().snapshot();
        return new IndexEpoch(
                false,
                residents.revision(),
                operations.revision(),
                residents.authorities().size(),
                residents.allResidents().size(),
                operations.operations().size());
    }

    @Nonnull
    private List<ResidentDetail> residentDetails(Connection connection) throws SQLException {
        ArrayList<ResidentDetail> details = new ArrayList<>(MAX_DETAIL_ROWS);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT world_name, coop_id, x, y, z, resident_slot, profile_id, state,
                       resident_uuid, source_npc_uuid, deployed_npc_uuid, generation
                FROM managed_coop_residents WHERE active = 1
                ORDER BY lower(world_name), x, y, z, resident_slot, profile_id, resident_id
                LIMIT ?
                """)) {
            statement.setInt(1, MAX_DETAIL_ROWS);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    details.add(new ResidentDetail(
                            authorityKey(rows),
                            requiredText(rows.getString("coop_id"), "coop_id"),
                            rows.getInt("resident_slot"),
                            requiredText(rows.getString("profile_id"), "profile_id"),
                            requiredText(rows.getString("state"), "state"),
                            requiredUuid(rows.getString("resident_uuid"), "resident_uuid"),
                            optionalUuid(rows.getString("source_npc_uuid")),
                            optionalUuid(rows.getString("deployed_npc_uuid")),
                            rows.getLong("generation")));
                }
            }
        }
        return List.copyOf(details);
    }

    @Nonnull
    private List<OperationDetail> operationDetails(Connection connection) throws SQLException {
        ArrayList<OperationDetail> details = new ArrayList<>(MAX_DETAIL_ROWS);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, operation_kind, profile_id, world_name, coop_id,
                       x, y, z, resident_slot, source_npc_uuid, planned_target_uuid,
                       actual_target_uuid, state, generation, retry_count, last_error
                FROM coop_lifecycle_operations WHERE active = 1
                ORDER BY lower(world_name), x, y, z, resident_slot, created_at_ms, operation_id
                LIMIT ?
                """)) {
            statement.setInt(1, MAX_DETAIL_ROWS);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    details.add(new OperationDetail(
                            authorityKey(rows),
                            requiredText(rows.getString("coop_id"), "coop_id"),
                            rows.getInt("resident_slot"),
                            requiredText(rows.getString("profile_id"), "profile_id"),
                            requiredText(rows.getString("operation_id"), "operation_id"),
                            requiredText(rows.getString("operation_kind"), "operation_kind"),
                            requiredText(rows.getString("state"), "state"),
                            optionalUuid(rows.getString("source_npc_uuid")),
                            optionalUuid(rows.getString("planned_target_uuid")),
                            optionalUuid(rows.getString("actual_target_uuid")),
                            rows.getLong("generation"),
                            rows.getInt("retry_count"),
                            rows.getString("last_error")));
                }
            }
        }
        return List.copyOf(details);
    }

    private ManagedCoopAuthorityKey authorityKey(ResultSet rows) throws SQLException {
        return new ManagedCoopAuthorityKey(
                requiredText(rows.getString("world_name"), "world_name"),
                rows.getInt("x"), rows.getInt("y"), rows.getInt("z"));
    }

    private String requiredText(String value, String field) throws SQLException {
        if (value == null || value.isBlank()) {
            throw new SQLException("managed_coop_diagnostic_missing_" + field);
        }
        return value.trim();
    }

    private UUID requiredUuid(String value, String field) throws SQLException {
        if (value == null || value.isBlank()) {
            throw new SQLException("managed_coop_diagnostic_missing_" + field);
        }
        return UUID.fromString(value.trim());
    }

    @Nullable
    private UUID optionalUuid(@Nullable String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value.trim());
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
    private record IndexEpoch(boolean trusted,
                              long residentRevision,
                              long operationRevision,
                              int authorityCount,
                              int residentCount,
                              int operationCount) {
    }

    /** Immutable resident identity and occupancy values safe to print outside the ECS thread. */
    public record ResidentDetail(@Nonnull ManagedCoopAuthorityKey authorityKey,
                                 @Nonnull String coopId,
                                 int residentSlot,
                                 @Nonnull String profileId,
                                 @Nonnull String state,
                                 @Nonnull UUID residentUuid,
                                 @Nullable UUID sourceNpcUuid,
                                 @Nullable UUID deployedNpcUuid,
                                 long generation) {
    }

    /** Immutable active-operation values, including projection and retry state. */
    public record OperationDetail(@Nonnull ManagedCoopAuthorityKey authorityKey,
                                  @Nonnull String coopId,
                                  int residentSlot,
                                  @Nonnull String profileId,
                                  @Nonnull String operationId,
                                  @Nonnull String kind,
                                  @Nonnull String state,
                                  @Nullable UUID sourceNpcUuid,
                                  @Nullable UUID plannedTargetUuid,
                                  @Nullable UUID actualTargetUuid,
                                  long generation,
                                  int retryCount,
                                  @Nullable String lastError) {
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
            @Nonnull List<ResidentDetail> residentDetails,
            @Nonnull List<OperationDetail> operationDetails,
            @Nullable String failureReason,
            @Nullable Throwable failure) {
        public AuditReport {
            Objects.requireNonNull(status, "status");
            activeAuthoritiesByState = orderedCopy(activeAuthoritiesByState);
            activeResidentsByState = orderedCopy(activeResidentsByState);
            activeOperationsByKindAndState = orderedCopy(activeOperationsByKindAndState);
            residentDetails = List.copyOf(residentDetails);
            operationDetails = List.copyOf(operationDetails);
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

    private static long total(Map<String, Long> counts) {
        long result = 0L;
        for (long count : counts.values()) {
            result += count;
        }
        return result;
    }
}
