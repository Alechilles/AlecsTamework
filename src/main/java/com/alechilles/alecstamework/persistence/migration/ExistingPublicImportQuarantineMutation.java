package com.alechilles.alecstamework.persistence.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import javax.annotation.Nullable;

/** Performs the fenced writes for one verified import-quarantine profile. */
final class ExistingPublicImportQuarantineMutation {
    void applyProfile(
            Connection connection,
            PublicImportPlan plan,
            PublicImportPlan.Lifecycle lifecycle,
            long repairedAtMs
    ) throws Exception {
        PublicImportPlan.Snapshot snapshot = plan.snapshots().stream()
                .filter(row -> row.profileId().equals(lifecycle.profileId())
                        && row.current())
                .findFirst()
                .orElseThrow();
        requireOne(updateCurrentSnapshot(connection, snapshot));
        if ("COOP".equals(lifecycle.state())) {
            applyCoopResidency(connection, plan, lifecycle);
        }
        String incidentId = incidentId(connection, lifecycle.profileId());
        requireOne(updateLifecycle(connection, lifecycle, incidentId));
        requireOne(releaseQuarantine(
                connection, lifecycle.profileId(), incidentId, repairedAtMs
        ));
        requireOne(resolveIncident(
                connection, incidentId, repairedAtMs
        ));
    }

    private int updateCurrentSnapshot(
            Connection connection,
            PublicImportPlan.Snapshot snapshot
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE companion_snapshot SET is_current = 1
                WHERE snapshot_id = ? AND profile_id = ?
                  AND source_lifecycle_revision = 0 AND is_current = 0
                """)) {
            statement.setString(1, snapshot.snapshotId());
            statement.setString(2, snapshot.profileId());
            return statement.executeUpdate();
        }
    }

    private void applyCoopResidency(
            Connection connection,
            PublicImportPlan plan,
            PublicImportPlan.Lifecycle lifecycle
    ) throws Exception {
        PublicImportPlan.CoopResidency residency = plan.coopResidencies()
                .stream()
                .filter(row -> row.profileId().equals(lifecycle.profileId()))
                .findFirst()
                .orElseThrow();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_residency(
                    coop_key, profile_id, housed_npc_uuid, snapshot_id,
                    captured_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, residency.coopKey());
            statement.setString(2, residency.profileId());
            setNullableString(statement, 3, residency.housedNpcUuid());
            statement.setString(4, residency.snapshotId());
            statement.setLong(5, residency.capturedAtMs());
            statement.setLong(6, residency.updatedAtMs());
            requireOne(statement.executeUpdate());
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE coop_slot SET residency_revision = 1
                WHERE coop_key = ? AND residency_revision = 0
                  AND active_operation_id IS NULL
                  AND reserved_profile_id IS NULL
                """)) {
            statement.setString(1, residency.coopKey());
            requireOne(statement.executeUpdate());
        }
    }

    private String incidentId(
            Connection connection,
            String profileId
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT incident_id FROM persistence_quarantine
                WHERE scope_type = 'PROFILE' AND scope_key = ?
                  AND state = 'ACTIVE' AND reason_code = ?
                """)) {
            statement.setString(1, profileId);
            statement.setString(
                    2,
                    ExistingPublicImportQuarantineTargetRepair.REASON
            );
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException(
                            "repair_quarantine_missing"
                    );
                }
                String incidentId = row.getString(1);
                if (row.next()) {
                    throw new IllegalStateException(
                            "repair_quarantine_ambiguous"
                    );
                }
                return incidentId;
            }
        }
    }

    private int updateLifecycle(
            Connection connection,
            PublicImportPlan.Lifecycle lifecycle,
            String incidentId
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE companion_lifecycle
                SET lifecycle_state = ?, location_kind = ?, location_key = ?,
                    revision = 1, quarantine_incident_id = NULL
                WHERE profile_id = ?
                  AND lifecycle_state = 'UNRESOLVED'
                  AND location_kind = 'UNRESOLVED'
                  AND location_key IS NULL AND world_key IS NULL
                  AND revision = 0 AND active_operation_id IS NULL
                  AND last_reconciled_generation = 0
                  AND state_changed_at_ms = ?
                  AND quarantine_incident_id = ?
                """)) {
            statement.setString(1, lifecycle.state());
            statement.setString(2, lifecycle.locationKind());
            setNullableString(statement, 3, lifecycle.locationKey());
            statement.setString(4, lifecycle.profileId());
            statement.setLong(5, lifecycle.changedAtMs());
            statement.setString(6, incidentId);
            return statement.executeUpdate();
        }
    }

    private int releaseQuarantine(
            Connection connection,
            String profileId,
            String incidentId,
            long repairedAtMs
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE persistence_quarantine
                SET state = 'RELEASED', released_at_ms = ?
                WHERE scope_type = 'PROFILE' AND scope_key = ?
                  AND incident_id = ? AND state = 'ACTIVE'
                  AND reason_code = ?
                """)) {
            statement.setLong(1, repairedAtMs);
            statement.setString(2, profileId);
            statement.setString(3, incidentId);
            statement.setString(
                    4,
                    ExistingPublicImportQuarantineTargetRepair.REASON
            );
            return statement.executeUpdate();
        }
    }

    private int resolveIncident(
            Connection connection,
            String incidentId,
            long repairedAtMs
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE persistence_incident
                SET state = 'RESOLVED', resolved_at_ms = ?
                WHERE incident_id = ? AND state = 'OPEN'
                  AND failure_kind = 'IMPORT_CONFLICT'
                  AND failure_code = ?
                """)) {
            statement.setLong(1, repairedAtMs);
            statement.setString(2, incidentId);
            statement.setString(
                    3,
                    ExistingPublicImportQuarantineTargetRepair.REASON
            );
            return statement.executeUpdate();
        }
    }

    private void requireOne(int changed) {
        if (changed != 1) {
            throw new IllegalStateException(
                    "repair_write_fence_mismatch"
            );
        }
    }

    private void setNullableString(
            PreparedStatement statement,
            int index,
            @Nullable String value
    ) throws Exception {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
