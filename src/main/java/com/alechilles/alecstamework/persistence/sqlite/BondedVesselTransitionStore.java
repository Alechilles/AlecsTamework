package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselSqlSupport.BINDING_COLUMNS;
import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselSqlSupport.OPERATION_COLUMNS;
import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselSqlSupport.readBinding;
import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselSqlSupport.readOperation;
import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselSqlSupport.setInteger;
import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselSqlSupport.setText;
import static com.alechilles.alecstamework.persistence.sqlite.BondedVesselSqlSupport.setUuid;

/** Performs mechanical bonded-vessel SQL writes under repository-level transition validation. */
final class BondedVesselTransitionStore {
    private BondedVesselTransitionStore() {
    }

    static void reserveOperation(Connection connection,
                                 BondedVesselOperationRecord operation) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bonded_vessel_bindings
                SET active_operation_id = ?, row_revision = row_revision + 1, updated_at_ms = ?
                WHERE binding_id = ? AND generation = ? AND active_operation_id IS NULL
                """)) {
            statement.setString(1, operation.operationId());
            statement.setLong(2, operation.updatedAtMs());
            statement.setString(3, operation.bindingId());
            statement.setLong(4, operation.priorGeneration());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Binding changed while installing transition reservation.");
            }
        }
    }

    static void updateBindingForClaim(Connection connection,
                                      BondedVesselOperationRecord operation,
                                      long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bonded_vessel_bindings
                SET lifecycle_state = ?, row_revision = row_revision + 1, updated_at_ms = ?
                WHERE binding_id = ? AND generation = ? AND active_operation_id = ?
                  AND lifecycle_state = ?
                """)) {
            statement.setString(1, operation.applyingLifecycleState().name());
            statement.setLong(2, nowMs);
            statement.setString(3, operation.bindingId());
            statement.setLong(4, operation.priorGeneration());
            statement.setString(5, operation.operationId());
            statement.setString(6, operation.priorLifecycleState().name());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Binding changed before claim-for-apply.");
            }
        }
    }

    static void updateBindingForApply(Connection connection,
                                      BondedVesselOperationRecord operation,
                                      BondedVesselRepository.AppliedTransition transition) throws Exception {
        BondedVesselBindingRecord.PhysicalLocation location = transition.activeLocation();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bonded_vessel_bindings
                SET generation = ?, lifecycle_state = ?, item_projection_status = ?,
                    expected_profile_revision = ?, active_npc_uuid = ?, active_world_name = ?,
                    active_chunk_x = ?, active_chunk_z = ?, cooldown_until_ms = ?,
                    last_item_id = ?, item_evidence_json = ?, diagnostic_reason = ?,
                    row_revision = row_revision + 1, updated_at_ms = ?,
                    released_at_ms = CASE WHEN ? = 'RELEASED' THEN ? ELSE released_at_ms END
                WHERE binding_id = ? AND generation = ? AND active_operation_id = ?
                  AND lifecycle_state = ?
                """)) {
            statement.setLong(1, operation.candidateGeneration());
            statement.setString(2, operation.targetLifecycleState().name());
            statement.setString(3, operation.targetProjectionStatus().name());
            statement.setLong(4, transition.committedProfileRevision());
            setUuid(statement, 5, transition.activeNpcUuid());
            setText(statement, 6, location == null ? null : location.worldName());
            setInteger(statement, 7, location == null ? null : location.chunkX());
            setInteger(statement, 8, location == null ? null : location.chunkZ());
            statement.setLong(9, operation.targetCooldownUntilMs());
            setText(statement, 10, operation.targetItemId());
            setText(statement, 11, transition.itemEvidenceJson());
            setText(statement, 12, transition.reasonCode());
            statement.setLong(13, transition.appliedAtMs());
            statement.setString(14, operation.targetLifecycleState().name());
            statement.setLong(15, transition.appliedAtMs());
            statement.setString(16, operation.bindingId());
            statement.setLong(17, operation.priorGeneration());
            statement.setString(18, operation.operationId());
            statement.setString(19, operation.applyingLifecycleState().name());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Binding changed during apply.");
            }
        }
    }

    static void updateOperationApplied(Connection connection,
                                       BondedVesselOperationRecord operation,
                                       BondedVesselRepository.AppliedTransition transition) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bonded_vessel_operations
                SET state = 'APPLIED', actual_npc_uuid = ?, reason_code = ?,
                    updated_at_ms = ?, applied_at_ms = ?
                WHERE operation_id = ? AND state = 'APPLYING'
                """)) {
            setUuid(statement, 1, transition.activeNpcUuid());
            setText(statement, 2, transition.reasonCode());
            statement.setLong(3, transition.appliedAtMs());
            statement.setLong(4, transition.appliedAtMs());
            statement.setString(5, operation.operationId());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Vessel operation changed during apply.");
            }
        }
    }

    static void updateOperationState(Connection connection, String operationId,
                                     BondedVesselOperationRecord.State expected,
                                     BondedVesselOperationRecord.State next,
                                     @Nullable String reason, long nowMs,
                                     long completedAtMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bonded_vessel_operations
                SET state = ?, reason_code = COALESCE(?, reason_code), updated_at_ms = ?,
                    completed_at_ms = ?
                WHERE operation_id = ? AND state = ?
                """)) {
            statement.setString(1, next.name());
            setText(statement, 2, reason);
            statement.setLong(3, nowMs);
            statement.setLong(4, completedAtMs);
            statement.setString(5, operationId);
            statement.setString(6, expected.name());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Vessel operation state changed unexpectedly.");
            }
        }
    }

    static void clearActiveOperation(Connection connection,
                                     BondedVesselOperationRecord operation,
                                     long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE bonded_vessel_bindings
                SET active_operation_id = NULL, row_revision = row_revision + 1, updated_at_ms = ?
                WHERE binding_id = ? AND generation = ? AND active_operation_id = ?
                """)) {
            statement.setLong(1, nowMs);
            statement.setString(2, operation.bindingId());
            statement.setLong(3, operation.priorGeneration());
            statement.setString(4, operation.operationId());
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Binding reservation changed before cancellation.");
            }
        }
    }

    static void insertBinding(Connection connection, BondedVesselBindingRecord binding) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bonded_vessel_bindings (
                    binding_id, profile_id, generation, config_id, config_revision,
                    lifecycle_state, item_projection_status, owner_uuid,
                    expected_profile_revision, active_npc_uuid, active_world_name,
                    active_chunk_x, active_chunk_z, cooldown_until_ms, last_item_id,
                    item_evidence_json, active_operation_id, diagnostic_reason,
                    row_revision, created_at_ms, updated_at_ms, released_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            BondedVesselSqlSupport.insertBinding(statement, binding);
            statement.executeUpdate();
        }
    }

    static void insertOperation(Connection connection, BondedVesselOperationRecord operation) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO bonded_vessel_operations (
                    operation_id, caller_namespace, idempotency_key, correlation_id,
                    binding_id, profile_id, action, state, prior_generation,
                    candidate_generation, expected_profile_revision, config_id, config_revision,
                    prior_lifecycle_state, applying_lifecycle_state, target_lifecycle_state,
                    prior_projection_status, target_projection_status,
                    prior_cooldown_until_ms, target_cooldown_until_ms,
                    source_item_id, target_item_id, source_fingerprint, replacement_fingerprint,
                    source_context_json, policy_snapshot_json, population_operation_id,
                    actual_npc_uuid, reason_code, recovery_status, lease_expires_at_ms,
                    created_at_ms, updated_at_ms, applied_at_ms, completed_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            BondedVesselSqlSupport.insertOperation(statement, operation);
            statement.executeUpdate();
        }
    }

    @Nullable
    static BondedVesselBindingRecord findBinding(Connection connection, String bindingId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                BINDING_COLUMNS + " WHERE binding_id = ? LIMIT 1")) {
            statement.setString(1, bindingId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readBinding(result) : null;
            }
        }
    }

    @Nullable
    static BondedVesselBindingRecord findBindingByProfile(Connection connection, String profileId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                BINDING_COLUMNS + " WHERE profile_id = ? AND lifecycle_state <> 'RELEASED' LIMIT 1")) {
            statement.setString(1, profileId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readBinding(result) : null;
            }
        }
    }

    @Nullable
    static BondedVesselOperationRecord findOperation(Connection connection, String operationId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                OPERATION_COLUMNS + " WHERE operation_id = ? LIMIT 1")) {
            statement.setString(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readOperation(result) : null;
            }
        }
    }

    @Nullable
    static BondedVesselOperationRecord findOperationByCallerKey(
            Connection connection, String callerNamespace, String idempotencyKey) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                OPERATION_COLUMNS + " WHERE caller_namespace = ? AND idempotency_key = ? LIMIT 1")) {
            statement.setString(1, callerNamespace);
            statement.setString(2, idempotencyKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readOperation(result) : null;
            }
        }
    }

    @Nullable
    static BondedVesselOperationRecord findExistingOperation(
            Connection connection, BondedVesselOperationRecord requested) throws Exception {
        BondedVesselOperationRecord byId = findOperation(connection, requested.operationId());
        return byId != null ? byId : findOperationByCallerKey(
                connection, requested.callerNamespace(), requested.idempotencyKey());
    }
}
