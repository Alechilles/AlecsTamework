package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Centralizes bonded-vessel SQL row mapping so transition code stays focused on fencing. */
final class BondedVesselSqlSupport {
    static final String BINDING_COLUMNS = """
            SELECT binding_id, profile_id, generation, config_id, config_revision,
                   lifecycle_state, item_projection_status, owner_uuid,
                   expected_profile_revision, active_npc_uuid, active_world_name,
                   active_chunk_x, active_chunk_z, cooldown_until_ms, last_item_id,
                   item_evidence_json, active_operation_id, diagnostic_reason,
                   row_revision, created_at_ms, updated_at_ms, released_at_ms
            FROM bonded_vessel_bindings
            """;

    static final String OPERATION_COLUMNS = """
            SELECT operation_id, caller_namespace, idempotency_key, correlation_id,
                   binding_id, profile_id, action, state, prior_generation,
                   candidate_generation, expected_profile_revision, config_id, config_revision,
                   prior_lifecycle_state, applying_lifecycle_state, target_lifecycle_state,
                   prior_projection_status, target_projection_status,
                   prior_cooldown_until_ms, target_cooldown_until_ms,
                   source_item_id, target_item_id, source_fingerprint, replacement_fingerprint,
                   source_context_json, policy_snapshot_json, population_operation_id,
                   actual_npc_uuid, reason_code, recovery_status, lease_expires_at_ms,
                   created_at_ms, updated_at_ms, applied_at_ms, completed_at_ms
            FROM bonded_vessel_operations
            """;

    private BondedVesselSqlSupport() {
    }

    static void insertBinding(@Nonnull PreparedStatement statement,
                              @Nonnull BondedVesselBindingRecord binding) throws Exception {
        int index = 1;
        statement.setString(index++, binding.bindingId());
        statement.setString(index++, binding.profileId());
        statement.setLong(index++, binding.generation());
        statement.setString(index++, binding.configId());
        statement.setLong(index++, binding.configRevision());
        statement.setString(index++, binding.lifecycleState().name());
        statement.setString(index++, binding.itemProjectionStatus().name());
        statement.setString(index++, binding.ownerUuid().toString());
        statement.setLong(index++, binding.expectedProfileRevision());
        setUuid(statement, index++, binding.activeNpcUuid());
        BondedVesselBindingRecord.PhysicalLocation location = binding.activeLocation();
        setText(statement, index++, location == null ? null : location.worldName());
        setInteger(statement, index++, location == null ? null : location.chunkX());
        setInteger(statement, index++, location == null ? null : location.chunkZ());
        statement.setLong(index++, binding.cooldownUntilMs());
        setText(statement, index++, binding.lastItemId());
        setText(statement, index++, binding.itemEvidenceJson());
        setText(statement, index++, binding.activeOperationId());
        setText(statement, index++, binding.diagnosticReason());
        statement.setLong(index++, binding.rowRevision());
        statement.setLong(index++, binding.createdAtMs());
        statement.setLong(index++, binding.updatedAtMs());
        statement.setLong(index, binding.releasedAtMs());
    }

    static void insertOperation(@Nonnull PreparedStatement statement,
                                @Nonnull BondedVesselOperationRecord operation) throws Exception {
        int index = 1;
        statement.setString(index++, operation.operationId());
        statement.setString(index++, operation.callerNamespace());
        statement.setString(index++, operation.idempotencyKey());
        setText(statement, index++, operation.correlationId());
        statement.setString(index++, operation.bindingId());
        statement.setString(index++, operation.profileId());
        statement.setString(index++, operation.action().name());
        statement.setString(index++, operation.state().name());
        statement.setLong(index++, operation.priorGeneration());
        statement.setLong(index++, operation.candidateGeneration());
        statement.setLong(index++, operation.expectedProfileRevision());
        statement.setString(index++, operation.configId());
        statement.setLong(index++, operation.configRevision());
        statement.setString(index++, operation.priorLifecycleState().name());
        statement.setString(index++, operation.applyingLifecycleState().name());
        statement.setString(index++, operation.targetLifecycleState().name());
        statement.setString(index++, operation.priorProjectionStatus().name());
        statement.setString(index++, operation.targetProjectionStatus().name());
        statement.setLong(index++, operation.priorCooldownUntilMs());
        statement.setLong(index++, operation.targetCooldownUntilMs());
        setText(statement, index++, operation.sourceItemId());
        setText(statement, index++, operation.targetItemId());
        setText(statement, index++, operation.sourceFingerprint());
        setText(statement, index++, operation.replacementFingerprint());
        setText(statement, index++, operation.sourceContextJson());
        statement.setString(index++, operation.policySnapshotJson());
        setText(statement, index++, operation.populationOperationId());
        setUuid(statement, index++, operation.actualNpcUuid());
        setText(statement, index++, operation.reasonCode());
        statement.setString(index++, operation.recoveryStatus());
        statement.setLong(index++, operation.leaseExpiresAtMs());
        statement.setLong(index++, operation.createdAtMs());
        statement.setLong(index++, operation.updatedAtMs());
        statement.setLong(index++, operation.appliedAtMs());
        statement.setLong(index, operation.completedAtMs());
    }

    @Nonnull
    static BondedVesselBindingRecord readBinding(@Nonnull ResultSet result) throws Exception {
        String world = result.getString("active_world_name");
        BondedVesselBindingRecord.PhysicalLocation location = world == null ? null
                : new BondedVesselBindingRecord.PhysicalLocation(
                        world, result.getInt("active_chunk_x"), result.getInt("active_chunk_z"));
        return new BondedVesselBindingRecord(
                result.getString("binding_id"),
                result.getString("profile_id"),
                result.getLong("generation"),
                result.getString("config_id"),
                result.getLong("config_revision"),
                BondedVesselBindingRecord.LifecycleState.valueOf(result.getString("lifecycle_state")),
                BondedVesselBindingRecord.ItemProjectionStatus.valueOf(
                        result.getString("item_projection_status")),
                UUID.fromString(result.getString("owner_uuid")),
                result.getLong("expected_profile_revision"),
                parseUuid(result.getString("active_npc_uuid")),
                location,
                result.getLong("cooldown_until_ms"),
                result.getString("last_item_id"),
                result.getString("item_evidence_json"),
                result.getString("active_operation_id"),
                result.getString("diagnostic_reason"),
                result.getLong("row_revision"),
                result.getLong("created_at_ms"),
                result.getLong("updated_at_ms"),
                result.getLong("released_at_ms")
        );
    }

    @Nonnull
    static BondedVesselOperationRecord readOperation(@Nonnull ResultSet result) throws Exception {
        return new BondedVesselOperationRecord(
                result.getString("operation_id"),
                result.getString("caller_namespace"),
                result.getString("idempotency_key"),
                result.getString("correlation_id"),
                result.getString("binding_id"),
                result.getString("profile_id"),
                BondedVesselOperationRecord.Action.valueOf(result.getString("action")),
                BondedVesselOperationRecord.State.valueOf(result.getString("state")),
                result.getLong("prior_generation"),
                result.getLong("candidate_generation"),
                result.getLong("expected_profile_revision"),
                result.getString("config_id"),
                result.getLong("config_revision"),
                BondedVesselBindingRecord.LifecycleState.valueOf(
                        result.getString("prior_lifecycle_state")),
                BondedVesselBindingRecord.LifecycleState.valueOf(
                        result.getString("applying_lifecycle_state")),
                BondedVesselBindingRecord.LifecycleState.valueOf(
                        result.getString("target_lifecycle_state")),
                BondedVesselBindingRecord.ItemProjectionStatus.valueOf(
                        result.getString("prior_projection_status")),
                BondedVesselBindingRecord.ItemProjectionStatus.valueOf(
                        result.getString("target_projection_status")),
                result.getLong("prior_cooldown_until_ms"),
                result.getLong("target_cooldown_until_ms"),
                result.getString("source_item_id"),
                result.getString("target_item_id"),
                result.getString("source_fingerprint"),
                result.getString("replacement_fingerprint"),
                result.getString("source_context_json"),
                result.getString("policy_snapshot_json"),
                result.getString("population_operation_id"),
                parseUuid(result.getString("actual_npc_uuid")),
                result.getString("reason_code"),
                result.getString("recovery_status"),
                result.getLong("lease_expires_at_ms"),
                result.getLong("created_at_ms"),
                result.getLong("updated_at_ms"),
                result.getLong("applied_at_ms"),
                result.getLong("completed_at_ms")
        );
    }

    static void setText(@Nonnull PreparedStatement statement, int index, @Nullable String value)
            throws Exception {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    static void setUuid(@Nonnull PreparedStatement statement, int index, @Nullable UUID value)
            throws Exception {
        setText(statement, index, value == null ? null : value.toString());
    }

    static void setInteger(@Nonnull PreparedStatement statement, int index, @Nullable Integer value)
            throws Exception {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    @Nullable
    static UUID parseUuid(@Nullable String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
