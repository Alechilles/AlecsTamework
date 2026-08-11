package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Applies one fenced repair plan to an existing replacement target. */
final class ExistingPublicImportQuarantineTargetRepair {
    static final String REASON = "MUTUALLY_EXCLUSIVE_LIFECYCLE_FLAGS";

    private final LongSupplier clock;
    private final ExistingPublicImportQuarantineMutation mutation =
            new ExistingPublicImportQuarantineMutation();

    ExistingPublicImportQuarantineTargetRepair(
            @Nonnull LongSupplier clock
    ) {
        if (clock == null) {
            throw new IllegalArgumentException("Repair clock is required");
        }
        this.clock = clock;
    }

    int apply(
            Path target,
            String sourceSha256,
            int sourceSchemaVersion,
            long completedAtMs,
            PublicImportPlan plan
    ) throws Exception {
        try (Connection connection =
                     new SqliteConnectionFactory(target).openWriterConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!manifestMatches(
                        connection, sourceSha256,
                        sourceSchemaVersion, completedAtMs
                )) {
                    connection.rollback();
                    return 0;
                }
                int repaired = 0;
                for (PublicImportPlan.Lifecycle lifecycle : plan.lifecycles()) {
                    if (lifecycle.incidentId() == null
                            && !"UNRESOLVED".equals(lifecycle.state())
                            && matchesImportedProfile(
                            connection, plan, lifecycle
                    )) {
                        mutation.applyProfile(
                                connection, plan, lifecycle,
                                clock.getAsLong()
                        );
                        repaired++;
                    }
                }
                verifyNoForeignKeyViolation(connection);
                connection.commit();
                return repaired;
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private boolean manifestMatches(
            Connection connection,
            String sourceSha256,
            int sourceSchemaVersion,
            long completedAtMs
    ) throws Exception {
        boolean exact;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM import_manifest
                WHERE source_sha256 = ?
                  AND source_schema_version = ?
                  AND completed_at_ms = ?
                """)) {
            statement.setString(1, sourceSha256);
            statement.setInt(2, sourceSchemaVersion);
            statement.setLong(3, completedAtMs);
            try (ResultSet row = statement.executeQuery()) {
                exact = row.next() && row.getInt(1) == 1;
            }
        }
        return exact && scalar(
                connection, "SELECT COUNT(*) FROM import_manifest"
        ) == 1;
    }

    private boolean matchesImportedProfile(
            Connection connection,
            PublicImportPlan plan,
            PublicImportPlan.Lifecycle lifecycle
    ) throws Exception {
        PublicImportPlan.Profile profile = plan.profiles().stream()
                .filter(row -> row.profileId().equals(lifecycle.profileId()))
                .findFirst()
                .orElse(null);
        if (profile == null
                || !profileMatches(connection, profile)
                || !lifecycleMatches(connection, lifecycle)
                || hasProfileOperation(connection, lifecycle.profileId())) {
            return false;
        }
        List<PublicImportPlan.Alias> aliases = plan.aliases().stream()
                .filter(row -> row.profileId().equals(lifecycle.profileId()))
                .sorted(Comparator.comparing(PublicImportPlan.Alias::npcUuid))
                .toList();
        List<PublicImportPlan.Snapshot> snapshots = plan.snapshots().stream()
                .filter(row -> row.profileId().equals(lifecycle.profileId()))
                .sorted(Comparator.comparing(PublicImportPlan.Snapshot::snapshotId))
                .toList();
        long selected = snapshots.stream()
                .filter(PublicImportPlan.Snapshot::current)
                .count();
        return selected == 1
                && aliasesEqual(readAliases(connection, lifecycle.profileId()), aliases)
                && snapshotsEqual(
                readSnapshots(connection, lifecycle.profileId()), snapshots
        ) && coopEvidenceMatches(connection, plan, lifecycle);
    }

    private boolean profileMatches(
            Connection connection,
            PublicImportPlan.Profile expected
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT display_name, role_id, metadata_json, metadata_hash,
                       last_known_world_key, created_at_ms, updated_at_ms,
                       last_active_at_ms, metadata_revision
                FROM companion_profile WHERE profile_id = ?
                """)) {
            statement.setString(1, expected.profileId());
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        && Objects.equals(expected.displayName(), row.getString(1))
                        && Objects.equals(expected.roleId(), row.getString(2))
                        && Objects.equals(expected.metadataJson(), row.getString(3))
                        && Objects.equals(expected.metadataHash(), row.getString(4))
                        && Objects.equals(
                        expected.lastKnownWorldKey(), row.getString(5)
                ) && expected.createdAtMs() == row.getLong(6)
                        && expected.updatedAtMs() == row.getLong(7)
                        && expected.lastActiveAtMs() == row.getLong(8)
                        && row.getLong(9) == 0
                        && !row.next();
            }
        }
    }

    private boolean lifecycleMatches(
            Connection connection,
            PublicImportPlan.Lifecycle expected
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT lifecycle.owner_uuid, lifecycle.owner_world_key,
                       lifecycle.state_changed_at_ms
                FROM companion_lifecycle lifecycle
                JOIN persistence_quarantine quarantine
                  ON quarantine.scope_type = 'PROFILE'
                 AND quarantine.scope_key = lifecycle.profile_id
                 AND quarantine.incident_id =
                     lifecycle.quarantine_incident_id
                JOIN persistence_incident incident
                  ON incident.incident_id = quarantine.incident_id
                WHERE lifecycle.profile_id = ?
                  AND lifecycle.lifecycle_state = 'UNRESOLVED'
                  AND lifecycle.location_kind = 'UNRESOLVED'
                  AND lifecycle.location_key IS NULL
                  AND lifecycle.world_key IS NULL
                  AND lifecycle.revision = 0
                  AND lifecycle.active_operation_id IS NULL
                  AND lifecycle.last_reconciled_generation = 0
                  AND quarantine.state = 'ACTIVE'
                  AND quarantine.reason_code = ?
                  AND incident.state = 'OPEN'
                  AND incident.failure_kind = 'IMPORT_CONFLICT'
                  AND incident.failure_code = ?
                """)) {
            statement.setString(1, expected.profileId());
            statement.setString(2, REASON);
            statement.setString(3, REASON);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        && Objects.equals(expected.ownerUuid(), row.getString(1))
                        && Objects.equals(
                        expected.ownerWorldKey(), row.getString(2)
                ) && expected.changedAtMs() == row.getLong(3)
                        && !row.next();
            }
        }
    }

    private boolean hasProfileOperation(
            Connection connection,
            String profileId
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM operation_participant
                WHERE scope_type = 'PROFILE' AND scope_key = ?
                """)) {
            statement.setString(1, profileId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && row.getLong(1) != 0;
            }
        }
    }

    private List<AliasRow> readAliases(
            Connection connection,
            String profileId
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT npc_uuid, alias_generation, alias_state,
                       lease_operation_id, mapped_at_ms, retired_at_ms
                FROM companion_alias
                WHERE profile_id = ? ORDER BY npc_uuid
                """)) {
            statement.setString(1, profileId);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<AliasRow> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new AliasRow(
                            rows.getString(1), rows.getLong(2),
                            rows.getString(3), rows.getString(4),
                            rows.getLong(5), nullableLong(rows, 6)
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    private boolean aliasesEqual(
            List<AliasRow> actual,
            List<PublicImportPlan.Alias> expected
    ) {
        if (actual.size() != expected.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            PublicImportPlan.Alias planned = expected.get(index);
            AliasRow stored = actual.get(index);
            if (!stored.equals(new AliasRow(
                    planned.npcUuid(), planned.generation(), planned.state(),
                    null, planned.mappedAtMs(), planned.retiredAtMs()
            ))) {
                return false;
            }
        }
        return true;
    }

    private List<SnapshotRow> readSnapshots(
            Connection connection,
            String profileId
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT snapshot_id, snapshot_kind, payload_version,
                       payload_json, payload_hash, source_lifecycle_revision,
                       is_current, created_at_ms
                FROM companion_snapshot
                WHERE profile_id = ? ORDER BY snapshot_id
                """)) {
            statement.setString(1, profileId);
            try (ResultSet rows = statement.executeQuery()) {
                ArrayList<SnapshotRow> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new SnapshotRow(
                            rows.getString(1), rows.getString(2),
                            rows.getInt(3), rows.getString(4),
                            rows.getString(5), rows.getLong(6),
                            rows.getInt(7) == 1, rows.getLong(8)
                    ));
                }
                return List.copyOf(result);
            }
        }
    }

    private boolean snapshotsEqual(
            List<SnapshotRow> actual,
            List<PublicImportPlan.Snapshot> expected
    ) {
        if (actual.size() != expected.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            PublicImportPlan.Snapshot planned = expected.get(index);
            SnapshotRow stored = actual.get(index);
            if (!stored.equals(new SnapshotRow(
                    planned.snapshotId(), planned.kind(),
                    planned.payloadVersion(), planned.payloadJson(),
                    planned.payloadHash(), 0, false, planned.createdAtMs()
            ))) {
                return false;
            }
        }
        return true;
    }

    private boolean coopEvidenceMatches(
            Connection connection,
            PublicImportPlan plan,
            PublicImportPlan.Lifecycle lifecycle
    ) throws Exception {
        if (hasCoopResidency(connection, lifecycle.profileId(), lifecycle.locationKey())) {
            return false;
        }
        if (!"COOP".equals(lifecycle.state())) {
            return true;
        }
        PublicImportPlan.CoopResidency residency = plan.coopResidencies()
                .stream()
                .filter(row -> row.profileId().equals(lifecycle.profileId()))
                .findFirst()
                .orElse(null);
        PublicImportPlan.CoopSlot slot = plan.coopSlots().stream()
                .filter(row -> row.coopKey().equals(lifecycle.locationKey()))
                .findFirst()
                .orElse(null);
        if (residency == null || slot == null) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT world_key, coop_id, x, y, z, resident_slot,
                       residency_revision, active_operation_id,
                       reserved_profile_id
                FROM coop_slot WHERE coop_key = ?
                """)) {
            statement.setString(1, slot.coopKey());
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                        && slot.worldKey().equals(row.getString(1))
                        && slot.coopId().equals(row.getString(2))
                        && slot.x() == row.getInt(3)
                        && slot.y() == row.getInt(4)
                        && slot.z() == row.getInt(5)
                        && slot.residentSlot() == row.getInt(6)
                        && row.getLong(7) == 0
                        && row.getString(8) == null
                        && row.getString(9) == null
                        && !row.next();
            }
        }
    }

    private boolean hasCoopResidency(
            Connection connection,
            String profileId,
            @Nullable String coopKey
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM coop_residency
                WHERE profile_id = ? OR (? IS NOT NULL AND coop_key = ?)
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, coopKey);
            statement.setString(3, coopKey);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && row.getLong(1) != 0;
            }
        }
    }

    private void verifyNoForeignKeyViolation(Connection connection)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(
                     "PRAGMA foreign_key_check"
             )) {
            if (row.next()) {
                throw new IllegalStateException(
                        "repair_foreign_key_violation"
                );
            }
        }
    }

    private long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            if (!row.next()) {
                throw new IllegalStateException("repair_query_empty");
            }
            return row.getLong(1);
        }
    }

    @Nullable
    private Long nullableLong(ResultSet rows, int column) throws Exception {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private record AliasRow(
            String npcUuid,
            long generation,
            String state,
            @Nullable String leaseOperationId,
            long mappedAtMs,
            @Nullable Long retiredAtMs
    ) {
    }

    private record SnapshotRow(
            String snapshotId,
            String kind,
            int payloadVersion,
            String payloadJson,
            String payloadHash,
            long sourceLifecycleRevision,
            boolean current,
            long createdAtMs
    ) {
    }
}
