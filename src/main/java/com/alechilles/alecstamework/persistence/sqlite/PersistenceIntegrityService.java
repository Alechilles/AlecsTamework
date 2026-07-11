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
