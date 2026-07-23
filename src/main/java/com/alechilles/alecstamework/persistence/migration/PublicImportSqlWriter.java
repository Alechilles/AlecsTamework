package com.alechilles.alecstamework.persistence.migration;

import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Inserts one immutable public import plan inside the caller-owned target transaction. */
final class PublicImportSqlWriter {
    void write(
            @Nonnull Connection connection,
            @Nonnull PublicImportPlan plan,
            @Nonnull PublicImportManifest manifest
    ) throws Exception {
        if (connection == null || plan == null || manifest == null) {
            throw new IllegalArgumentException("Target connection, plan, and manifest required");
        }
        insertProfiles(connection, plan);
        insertIncidents(connection, plan);
        insertAliases(connection, plan);
        insertSnapshots(connection, plan);
        insertToolLinks(connection, plan);
        insertExtensions(connection, plan);
        insertCoopSlots(connection, plan);
        insertLifecycles(connection, plan);
        insertCoopResidencies(connection, plan);
        insertQuarantines(connection, plan);
        insertManifest(connection, manifest);
    }

    private void insertProfiles(Connection connection, PublicImportPlan plan) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_profile(
                    profile_id, display_name, role_id, metadata_json, metadata_hash,
                    last_known_world_key, created_at_ms, updated_at_ms,
                    last_active_at_ms, metadata_revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """)) {
            for (PublicImportPlan.Profile profile : plan.profiles()) {
                statement.setString(1, profile.profileId());
                setNullableString(statement, 2, profile.displayName());
                setNullableString(statement, 3, profile.roleId());
                setNullableString(statement, 4, profile.metadataJson());
                setNullableString(statement, 5, profile.metadataHash());
                setNullableString(statement, 6, profile.lastKnownWorldKey());
                statement.setLong(7, profile.createdAtMs());
                statement.setLong(8, profile.updatedAtMs());
                statement.setLong(9, profile.lastActiveAtMs());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertIncidents(Connection connection, PublicImportPlan plan) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO persistence_incident(
                    incident_id, failure_kind, failure_code, state,
                    summary, evidence_json, created_at_ms, resolved_at_ms
                ) VALUES (?, 'IMPORT_CONFLICT', ?, 'OPEN', ?, ?, ?, NULL)
                """)) {
            for (PublicImportPlan.Incident incident : plan.incidents()) {
                statement.setString(1, incident.incidentId());
                statement.setString(2, incident.reasonCode());
                statement.setString(3, "Public import conflict for profile " + incident.profileId());
                statement.setString(4, incident.evidenceJson());
                statement.setLong(5, incident.createdAtMs());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertAliases(Connection connection, PublicImportPlan plan) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_alias(
                    npc_uuid, profile_id, alias_generation, alias_state,
                    lease_operation_id, mapped_at_ms, retired_at_ms
                ) VALUES (?, ?, ?, ?, NULL, ?, ?)
                """)) {
            for (PublicImportPlan.Alias alias : plan.aliases()) {
                statement.setString(1, alias.npcUuid());
                statement.setString(2, alias.profileId());
                statement.setLong(3, alias.generation());
                statement.setString(4, alias.state());
                statement.setLong(5, alias.mappedAtMs());
                setNullableLong(statement, 6, alias.retiredAtMs());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertSnapshots(Connection connection, PublicImportPlan plan) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_snapshot(
                    snapshot_id, profile_id, snapshot_kind, payload_version,
                    payload_json, payload_hash, source_lifecycle_revision,
                    is_current, created_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
                """)) {
            for (PublicImportPlan.Snapshot snapshot : plan.snapshots()) {
                statement.setString(1, snapshot.snapshotId());
                statement.setString(2, snapshot.profileId());
                statement.setString(3, snapshot.kind());
                statement.setInt(4, snapshot.payloadVersion());
                statement.setString(5, snapshot.payloadJson());
                statement.setString(6, snapshot.payloadHash());
                statement.setInt(7, snapshot.current() ? 1 : 0);
                statement.setLong(8, snapshot.createdAtMs());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertToolLinks(Connection connection, PublicImportPlan plan) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_tool_link(
                    profile_id, tool_uuid, link_type, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            for (PublicImportPlan.ToolLink link : plan.toolLinks()) {
                statement.setString(1, link.profileId());
                statement.setString(2, link.toolUuid());
                statement.setString(3, link.linkType());
                statement.setLong(4, link.createdAtMs());
                statement.setLong(5, link.updatedAtMs());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertExtensions(Connection connection, PublicImportPlan plan) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO profile_extension_data(
                    profile_id, namespace, data_key, payload_version, json_payload,
                    payload_hash, revision, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, 1, ?, ?, 1, ?, ?)
                """)) {
            for (PublicImportPlan.ExtensionData extension : plan.extensionData()) {
                statement.setString(1, extension.profileId());
                statement.setString(2, extension.namespace());
                statement.setString(3, extension.dataKey());
                statement.setString(4, extension.jsonPayload());
                statement.setString(5, Sha256Hash.ofUtf8(
                        extension.jsonPayload()
                ).toString());
                statement.setLong(6, extension.createdAtMs());
                statement.setLong(7, extension.updatedAtMs());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertCoopSlots(Connection connection, PublicImportPlan plan) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_slot(
                    coop_key, world_key, coop_id, x, y, z, resident_slot
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (PublicImportPlan.CoopSlot slot : plan.coopSlots()) {
                statement.setString(1, slot.coopKey());
                statement.setString(2, slot.worldKey());
                statement.setString(3, slot.coopId());
                statement.setInt(4, slot.x());
                statement.setInt(5, slot.y());
                statement.setInt(6, slot.z());
                statement.setInt(7, slot.residentSlot());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertLifecycles(Connection connection, PublicImportPlan plan) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO companion_lifecycle(
                    profile_id, owner_uuid, lifecycle_state, location_kind,
                    location_key, world_key, revision, active_operation_id,
                    state_changed_at_ms, last_reconciled_generation, quarantine_incident_id
                ) VALUES (?, ?, ?, ?, ?, NULL, 0, NULL, ?, 0, ?)
                """)) {
            for (PublicImportPlan.Lifecycle lifecycle : plan.lifecycles()) {
                statement.setString(1, lifecycle.profileId());
                setNullableString(statement, 2, lifecycle.ownerUuid());
                statement.setString(3, lifecycle.state());
                statement.setString(4, lifecycle.locationKind());
                setNullableString(statement, 5, lifecycle.locationKey());
                statement.setLong(6, lifecycle.changedAtMs());
                setNullableString(statement, 7, lifecycle.incidentId());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertCoopResidencies(Connection connection, PublicImportPlan plan)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coop_residency(
                    coop_key, profile_id, housed_npc_uuid, snapshot_id,
                    captured_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            for (PublicImportPlan.CoopResidency residency : plan.coopResidencies()) {
                statement.setString(1, residency.coopKey());
                statement.setString(2, residency.profileId());
                setNullableString(statement, 3, residency.housedNpcUuid());
                statement.setString(4, residency.snapshotId());
                statement.setLong(5, residency.capturedAtMs());
                statement.setLong(6, residency.updatedAtMs());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE coop_slot
                SET residency_revision = 1
                WHERE coop_key IN (SELECT coop_key FROM coop_residency)
                """)) {
            statement.executeUpdate();
        }
    }

    private void insertQuarantines(Connection connection, PublicImportPlan plan) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO persistence_quarantine(
                    scope_type, scope_key, incident_id, state,
                    reason_code, created_at_ms, released_at_ms
                ) VALUES ('PROFILE', ?, ?, 'ACTIVE', ?, ?, NULL)
                """)) {
            for (PublicImportPlan.Incident incident : plan.incidents()) {
                statement.setString(1, incident.profileId());
                statement.setString(2, incident.incidentId());
                statement.setString(3, incident.reasonCode());
                statement.setLong(4, incident.createdAtMs());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertManifest(Connection connection, PublicImportManifest manifest)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO import_manifest(
                    import_id, source_sha256, source_schema_version, importer_version,
                    source_snapshot_name, counts_json, completed_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, manifest.importId());
            statement.setString(2, manifest.sourceSha256());
            statement.setInt(3, manifest.sourceSchemaVersion());
            statement.setInt(4, manifest.importerVersion());
            statement.setString(5, manifest.sourceSnapshotName());
            statement.setString(6, manifest.countsJson());
            statement.setLong(7, manifest.completedAtMs());
            statement.executeUpdate();
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

    private void setNullableLong(
            PreparedStatement statement,
            int index,
            @Nullable Long value
    ) throws Exception {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}
