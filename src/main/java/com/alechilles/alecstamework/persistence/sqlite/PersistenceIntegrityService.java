package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Runs explicit, read-only SQLite and cross-table uniqueness diagnostics. */
public final class PersistenceIntegrityService {
    private final SqliteConnectionManager connectionManager;

    public PersistenceIntegrityService(@Nonnull SqliteConnectionManager connectionManager) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
    }

    /** Returns every detected issue; read or integrity failures never masquerade as a clean report. */
    @Nonnull
    public IntegrityReport inspect() {
        ArrayList<IntegrityIssue> issues = new ArrayList<>();
        try (Connection connection = connectionManager.openConnection()) {
            inspectPragmaIntegrity(connection, issues);
            inspectForeignKeys(connection, issues);
            for (DuplicateCheck check : duplicateChecks()) {
                inspectDuplicateCheck(connection, check, issues);
            }
            return new IntegrityReport(ReportStatus.COMPLETE, issues, null, null);
        } catch (SQLException | RuntimeException exception) {
            return new IntegrityReport(
                    ReportStatus.FAILED,
                    issues,
                    exception.getMessage() == null ? "integrity_read_failed" : exception.getMessage(),
                    exception
            );
        }
    }

    private void inspectPragmaIntegrity(@Nonnull Connection connection,
                                        @Nonnull List<IntegrityIssue> issues) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA integrity_check")) {
            boolean sawRow = false;
            while (rows.next()) {
                sawRow = true;
                String detail = rows.getString(1);
                if (!"ok".equalsIgnoreCase(detail)) {
                    issues.add(new IntegrityIssue("sqlite_integrity", detail, 1L));
                }
            }
            if (!sawRow) {
                issues.add(new IntegrityIssue("sqlite_integrity", "no_result", 1L));
            }
        }
    }

    private void inspectForeignKeys(@Nonnull Connection connection,
                                    @Nonnull List<IntegrityIssue> issues) throws SQLException {
        long violations = 0L;
        String first = null;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
            while (rows.next()) {
                violations++;
                if (first == null) {
                    first = rows.getString(1) + ":rowid=" + rows.getString(2)
                            + ":parent=" + rows.getString(3);
                }
            }
        }
        if (violations > 0L) {
            issues.add(new IntegrityIssue("foreign_key_violation", first, violations));
        }
    }

    private void inspectDuplicateCheck(@Nonnull Connection connection,
                                       @Nonnull DuplicateCheck check,
                                       @Nonnull List<IntegrityIssue> issues) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(check.sql())) {
            if (!rows.next()) {
                return;
            }
            long groups = rows.getLong(1);
            if (groups > 0L) {
                issues.add(new IntegrityIssue(check.id(), check.detail(), groups));
            }
        }
    }

    @Nonnull
    private List<DuplicateCheck> duplicateChecks() {
        return List.of(
                check("duplicate_profile_current_uuid", "one current entity UUID maps to multiple profiles", """
                        SELECT COUNT(*) FROM (
                          SELECT current_npc_uuid FROM npc_profiles
                          WHERE current_npc_uuid IS NOT NULL
                          GROUP BY current_npc_uuid HAVING COUNT(*) > 1
                        )
                        """),
                check("multiple_current_aliases", "one profile has multiple current aliases", """
                        SELECT COUNT(*) FROM (
                          SELECT profile_id FROM npc_uuid_aliases WHERE is_current = 1
                          GROUP BY profile_id HAVING COUNT(*) > 1
                        )
                        """),
                check("duplicate_active_managed_profile", "profile occupies multiple active managed slots", """
                        SELECT COUNT(*) FROM (
                          SELECT profile_id FROM managed_coop_residents WHERE active = 1
                          GROUP BY profile_id HAVING COUNT(*) > 1
                        )
                        """),
                check("duplicate_active_managed_uuid", "UUID appears in multiple active managed residents", """
                        SELECT COUNT(*) FROM (
                          SELECT resident_uuid FROM managed_coop_residents WHERE active = 1
                          GROUP BY resident_uuid HAVING COUNT(*) > 1
                        )
                        """),
                check("duplicate_active_managed_slot", "managed slot has multiple active residents", """
                        SELECT COUNT(*) FROM (
                          SELECT authority_id, resident_slot FROM managed_coop_residents WHERE active = 1
                          GROUP BY authority_id, resident_slot HAVING COUNT(*) > 1
                        )
                        """),
                check("duplicate_active_coop_operation_profile", "profile has multiple active coop operations", """
                        SELECT COUNT(*) FROM (
                          SELECT profile_id FROM coop_lifecycle_operations WHERE active = 1
                          GROUP BY profile_id HAVING COUNT(*) > 1
                        )
                        """),
                check("duplicate_active_coop_operation_slot", "managed slot has multiple active coop operations", """
                        SELECT COUNT(*) FROM (
                          SELECT authority_id, resident_slot FROM coop_lifecycle_operations WHERE active = 1
                          GROUP BY authority_id, resident_slot HAVING COUNT(*) > 1
                        )
                        """),
                check("duplicate_active_managed_authority_location", "physical coop has multiple active authorities", """
                        SELECT COUNT(*) FROM (
                          SELECT world_name, x, y, z FROM managed_coop_authority WHERE active = 1
                          GROUP BY world_name, x, y, z HAVING COUNT(*) > 1
                        )
                        """),
                check("active_terminal_coop_operation", "terminal coop operation is still marked active", """
                        SELECT COUNT(*) FROM coop_lifecycle_operations
                        WHERE active = 1
                          AND state IN ('FINALIZED', 'COMPLETE', 'FAILED', 'QUARANTINED')
                        """),
                check("inactive_nonterminal_coop_operation", "nonterminal coop operation is marked inactive", """
                        SELECT COUNT(*) FROM coop_lifecycle_operations
                        WHERE active = 0
                          AND state IN ('PREPARED', 'SLOT_COMMITTED', 'SOURCE_RETIRE_REQUESTED',
                                        'SPAWN_CLAIMED', 'PROJECTION_CREATED')
                        """),
                check("active_finalized_import_session", "finalized coop import session is still marked active", """
                        SELECT COUNT(*) FROM managed_coop_import_sessions
                        WHERE active = 1 AND state <> 'ACTIVE'
                        """),
                check("inactive_active_import_session", "active coop import session is marked inactive", """
                        SELECT COUNT(*) FROM managed_coop_import_sessions
                        WHERE active = 0 AND state = 'ACTIVE'
                        """),
                check("import_session_source_count_mismatch", "import audit source count differs from immutable source rows", """
                        SELECT COUNT(*) FROM (
                          SELECT s.session_id
                          FROM managed_coop_import_sessions s
                          LEFT JOIN managed_coop_import_sources r ON r.session_id = s.session_id
                          GROUP BY s.session_id, s.source_count
                          HAVING COUNT(r.source_id) <> s.source_count
                        )
                        """),
                check("finalized_import_source_not_absent", "finalized import retained a matched/imported vanilla source without absence proof", """
                        SELECT COUNT(*) FROM (
                          SELECT s.session_id
                          FROM managed_coop_import_sessions s
                          JOIN managed_coop_import_sources r ON r.session_id = s.session_id
                          WHERE s.active = 0
                            AND s.state IN ('FINALIZED_MANAGED', 'FINALIZED_CONFLICT')
                            AND r.disposition_kind IN ('MATCHED', 'IMPORTED')
                            AND r.neutralization_state <> 'VERIFIED_ABSENT'
                          GROUP BY s.session_id
                        )
                        """),
                check("managed_authority_with_active_import", "active import session authority is not importing", """
                        SELECT COUNT(*) FROM (
                          SELECT a.authority_id
                          FROM managed_coop_authority a
                          JOIN managed_coop_import_sessions s ON s.authority_id = a.authority_id
                          WHERE s.active = 1
                            AND (a.active <> 1 OR a.authority_state <> 'IMPORTING_TO_TWORK')
                          GROUP BY a.authority_id
                        )
                        """),
                check("importing_authority_without_active_session", "importing authority has no active import session", """
                        SELECT COUNT(*) FROM managed_coop_authority a
                        WHERE a.active = 1 AND a.authority_state = 'IMPORTING_TO_TWORK'
                          AND NOT EXISTS (
                            SELECT 1 FROM managed_coop_import_sessions s
                            WHERE s.authority_id = a.authority_id
                              AND s.active = 1 AND s.state = 'ACTIVE'
                          )
                        """),
                check("unresolved_coop_import_conflict", "coop import conflicts require explicit resolution", """
                        SELECT COUNT(*) FROM coop_import_conflicts
                        WHERE resolution_state = 'UNRESOLVED'
                        """),
                check("duplicate_active_recovery_profile", "profile has multiple active recovery operations", """
                        SELECT COUNT(*) FROM (
                          SELECT profile_id FROM npc_recovery_operations WHERE active = 1
                          GROUP BY profile_id HAVING COUNT(*) > 1
                        )
                        """),
                check("duplicate_active_lost_snapshot", "profile has multiple active lost snapshots", """
                        SELECT COUNT(*) FROM (
                          SELECT profile_id FROM npc_snapshots
                          WHERE snapshot_type = 'lost' AND is_active = 1
                          GROUP BY profile_id HAVING COUNT(*) > 1
                        )
                        """)
        );
    }

    @Nonnull
    private DuplicateCheck check(@Nonnull String id,
                                 @Nonnull String detail,
                                 @Nonnull String sql) {
        return new DuplicateCheck(id, detail, sql);
    }

    public enum ReportStatus {
        COMPLETE,
        FAILED
    }

    public record IntegrityIssue(@Nonnull String id,
                                 @Nullable String detail,
                                 long affectedGroups) {
    }

    public record IntegrityReport(@Nonnull ReportStatus status,
                                  @Nonnull List<IntegrityIssue> issues,
                                  @Nullable String failureReason,
                                  @Nullable Throwable failure) {
        public IntegrityReport {
            issues = List.copyOf(issues);
        }

        public boolean isClean() {
            return status == ReportStatus.COMPLETE && issues.isEmpty();
        }
    }

    private record DuplicateCheck(@Nonnull String id,
                                  @Nonnull String detail,
                                  @Nonnull String sql) {
    }
}
