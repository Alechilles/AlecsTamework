package com.alechilles.alecstamework.persistence.sqlite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryFinalization;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryOperation;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryState;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.TransitionStatus;

/**
 * Validates and atomically commits the durable identity side effects of lost-NPC recovery.
 */
final class NpcRecoveryFinalizationStore {
    private static final String PROFILE_LINK_TYPE = "profile";
    private static final String LOST_SNAPSHOT_TYPE = "lost";

    private final NpcRecoveryConflictStore conflictStore;
    private final NpcRecoveryFinalizationVerifier verifier;

    NpcRecoveryFinalizationStore(@Nonnull NpcRecoveryConflictStore conflictStore) {
        this.conflictStore = Objects.requireNonNull(conflictStore, "conflictStore");
        this.verifier = new NpcRecoveryFinalizationVerifier();
    }

    TransitionStatus finalizeRecovery(@Nonnull Connection connection, @Nonnull RecoveryOperation operation,
                                      @Nonnull RecoveryFinalization finalization, long nowMs) throws Exception {
        TransitionStatus identityConflict = validateOperationIdentity(operation, finalization);
        if (identityConflict != null) {
            return identityConflict;
        }
        if (!operation.active() && operation.state() == RecoveryState.FINALIZED) {
            if (!Objects.equals(operation.actualTargetUuid(), finalization.actualTargetUuid())) {
                return TransitionStatus.TARGET_CONFLICT;
            }
            if (finalization.expectedGeneration() == Long.MAX_VALUE
                    || operation.generation() != finalization.expectedGeneration() + 1L) {
                return TransitionStatus.GENERATION_CONFLICT;
            }
            return verifier.verify(connection, operation, finalization);
        }
        if (!operation.active() || operation.state() != RecoveryState.PROJECTION_CREATED) {
            return TransitionStatus.STATE_CONFLICT;
        }
        if (!Objects.equals(operation.actualTargetUuid(), finalization.actualTargetUuid())) {
            return TransitionStatus.TARGET_CONFLICT;
        }
        if (operation.generation() != finalization.expectedGeneration()) {
            return TransitionStatus.GENERATION_CONFLICT;
        }

        TransitionStatus stateConflict = validateAwaitingRecoveryState(connection, finalization);
        if (stateConflict != null) {
            return stateConflict;
        }
        LostSnapshotRow snapshot = requireActiveLostSnapshot(connection, finalization.profileId());
        if (readReplacementUuid(snapshot.payload(), finalization.profileId(), false) != null) {
            return TransitionStatus.STATE_CONFLICT;
        }

        applyFinalization(connection, operation, finalization, snapshot, nowMs);
        return TransitionStatus.APPLIED;
    }

    private TransitionStatus validateOperationIdentity(RecoveryOperation operation,
                                                        RecoveryFinalization finalization) {
        if (!operation.profileId().equals(finalization.profileId())) {
            return TransitionStatus.PROFILE_CONFLICT;
        }
        if (!Objects.equals(operation.sourceNpcUuid(), finalization.sourceNpcUuid())) {
            return TransitionStatus.SOURCE_CONFLICT;
        }
        if (!finalization.plannedTargetUuid().equals(finalization.actualTargetUuid())
                || !Objects.equals(operation.plannedTargetUuid(), finalization.plannedTargetUuid())) {
            return TransitionStatus.TARGET_CONFLICT;
        }
        if (finalization.sourceNpcUuid() != null
                && finalization.sourceNpcUuid().equals(finalization.plannedTargetUuid())) {
            return TransitionStatus.SOURCE_CONFLICT;
        }
        return null;
    }

    private TransitionStatus validateAwaitingRecoveryState(Connection connection,
                                                            RecoveryFinalization finalization) throws SQLException {
        UUID currentNpcUuid = requireProfileCurrentUuid(connection, finalization.profileId());
        if (!Objects.equals(currentNpcUuid, finalization.sourceNpcUuid())) {
            return TransitionStatus.SOURCE_CONFLICT;
        }
        if (conflictStore.sourceMapsToDifferentProfile(
                connection, finalization.profileId(), finalization.sourceNpcUuid())) {
            return TransitionStatus.SOURCE_CONFLICT;
        }
        TransitionStatus targetOwnership = targetOwnershipConflict(
                connection, finalization.profileId(), finalization.plannedTargetUuid());
        if (targetOwnership != null) {
            return targetOwnership;
        }
        if (conflictStore.targetHasCrossDomainEvidence(connection, finalization.plannedTargetUuid())) {
            return TransitionStatus.TARGET_CONFLICT;
        }

        ProfileStateRow state = requireProfileState(connection, finalization.profileId());
        if (state.captureActive() || state.deathActive() || !state.lostActive() || state.inCoop()) {
            return TransitionStatus.STATE_CONFLICT;
        }
        if (hasActiveCoopConflict(connection, finalization.profileId())) {
            return TransitionStatus.STATE_CONFLICT;
        }
        return null;
    }

    private void applyFinalization(Connection connection, RecoveryOperation operation,
                                   RecoveryFinalization finalization, LostSnapshotRow snapshot,
                                   long nowMs) throws Exception {
        finalizeOperationRow(connection, operation, finalization, nowMs);
        remapProfileCurrentUuid(connection, finalization, nowMs);
        remapAliases(connection, finalization, nowMs);
        updateLostSnapshot(connection, snapshot, finalization, nowMs);
        mergeToolLinks(connection, finalization.profileId(), finalization.toolIds(), nowMs);
    }

    private void finalizeOperationRow(Connection connection, RecoveryOperation operation,
                                      RecoveryFinalization finalization, long nowMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE npc_recovery_operations
                SET state = 'FINALIZED', active = 0, generation = generation + 1,
                    updated_at_ms = ?, completed_at_ms = ?, last_error = NULL
                WHERE operation_id = ? AND profile_id = ?
                  AND ((source_npc_uuid IS NULL AND ? IS NULL) OR source_npc_uuid = ?)
                  AND planned_target_uuid = ? AND actual_target_uuid = ?
                  AND state = 'PROJECTION_CREATED' AND active = 1 AND generation = ?
                """)) {
            statement.setLong(1, nowMs);
            statement.setLong(2, nowMs);
            statement.setString(3, finalization.operationId());
            statement.setString(4, finalization.profileId());
            bindUuid(statement, 5, finalization.sourceNpcUuid());
            bindUuid(statement, 6, finalization.sourceNpcUuid());
            bindUuid(statement, 7, finalization.plannedTargetUuid());
            bindUuid(statement, 8, finalization.actualTargetUuid());
            statement.setLong(9, finalization.expectedGeneration());
            if (statement.executeUpdate() != 1) {
                throw integrity("recovery_operation_changed_during_finalize:" + operation.operationId());
            }
        }
    }

    private void remapProfileCurrentUuid(Connection connection, RecoveryFinalization finalization,
                                         long nowMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE npc_profiles
                SET current_npc_uuid = ?, updated_at_ms = ?, last_active_at_ms = ?
                WHERE profile_id = ?
                  AND ((current_npc_uuid IS NULL AND ? IS NULL) OR current_npc_uuid = ?)
                """)) {
            bindUuid(statement, 1, finalization.plannedTargetUuid());
            statement.setLong(2, nowMs);
            statement.setLong(3, nowMs);
            statement.setString(4, finalization.profileId());
            bindUuid(statement, 5, finalization.sourceNpcUuid());
            bindUuid(statement, 6, finalization.sourceNpcUuid());
            if (statement.executeUpdate() != 1) {
                throw integrity("recovery_profile_changed_during_finalize:" + finalization.profileId());
            }
        }
    }

    private void remapAliases(Connection connection, RecoveryFinalization finalization,
                              long nowMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE npc_uuid_aliases SET is_current = 0, mapped_at_ms = ? WHERE profile_id = ?")) {
            statement.setLong(1, nowMs);
            statement.setString(2, finalization.profileId());
            statement.executeUpdate();
        }
        if (finalization.sourceNpcUuid() != null) {
            upsertAlias(connection, finalization.sourceNpcUuid(), finalization.profileId(), false, nowMs);
        }
        upsertAlias(connection, finalization.plannedTargetUuid(), finalization.profileId(), true, nowMs);
    }

    private void upsertAlias(Connection connection, UUID npcUuid, String profileId,
                             boolean current, long nowMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO npc_uuid_aliases (npc_uuid, profile_id, is_current, mapped_at_ms)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(npc_uuid) DO UPDATE SET
                    profile_id = excluded.profile_id,
                    is_current = excluded.is_current,
                    mapped_at_ms = excluded.mapped_at_ms
                """)) {
            bindUuid(statement, 1, npcUuid);
            statement.setString(2, profileId);
            statement.setInt(3, current ? 1 : 0);
            statement.setLong(4, nowMs);
            statement.executeUpdate();
        }
    }

    private void updateLostSnapshot(Connection connection, LostSnapshotRow snapshot,
                                    RecoveryFinalization finalization, long nowMs) throws SQLException {
        JsonObject updatedPayload = snapshot.payload().deepCopy();
        updatedPayload.addProperty("replacementNpcUuid", finalization.plannedTargetUuid().toString());
        updatedPayload.addProperty("recoveredAtMs", nowMs);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE npc_snapshots SET payload_json = ?
                WHERE snapshot_id = ? AND profile_id = ? AND snapshot_type = 'lost' AND is_active = 1
                """)) {
            statement.setString(1, updatedPayload.toString());
            statement.setLong(2, snapshot.snapshotId());
            statement.setString(3, finalization.profileId());
            if (statement.executeUpdate() != 1) {
                throw integrity("active_lost_snapshot_changed_during_finalize:" + finalization.profileId());
            }
        }
    }

    private void mergeToolLinks(Connection connection, String profileId, List<String> toolIds,
                                long nowMs) throws SQLException {
        if (toolIds.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO npc_tool_links (profile_id, tool_uuid, link_type, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(profile_id, tool_uuid, link_type) DO UPDATE SET
                    updated_at_ms = excluded.updated_at_ms
                """)) {
            for (String toolId : toolIds) {
                statement.setString(1, profileId);
                statement.setString(2, toolId);
                statement.setString(3, PROFILE_LINK_TYPE);
                statement.setLong(4, nowMs);
                statement.setLong(5, nowMs);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private LostSnapshotRow requireActiveLostSnapshot(Connection connection,
                                                      String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT snapshot_id, snapshot_version, payload_json
                FROM npc_snapshots
                WHERE profile_id = ? AND snapshot_type = ? AND is_active = 1
                ORDER BY created_at_ms DESC, snapshot_id LIMIT 2
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, LOST_SNAPSHOT_TYPE);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw integrity("active_lost_snapshot_missing:" + profileId);
                }
                long snapshotId = resultSet.getLong("snapshot_id");
                int snapshotVersion = resultSet.getInt("snapshot_version");
                JsonObject payload = parsePayload(resultSet.getString("payload_json"), profileId);
                if (snapshotId <= 0L || snapshotVersion <= 0) {
                    throw integrity("invalid_active_lost_snapshot_metadata:" + profileId);
                }
                if (resultSet.next()) {
                    throw integrity("multiple_active_lost_snapshots:" + profileId);
                }
                return new LostSnapshotRow(snapshotId, payload);
            }
        }
    }

    private JsonObject parsePayload(String payloadJson, String profileId) {
        try {
            return JsonParser.parseString(payloadJson == null ? "" : payloadJson).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw integrity("invalid_active_lost_snapshot_json:" + profileId, exception);
        }
    }

    private UUID readReplacementUuid(JsonObject payload, String profileId, boolean required) {
        JsonElement replacement = payload.get("replacementNpcUuid");
        if (replacement == null) {
            if (required) {
                throw integrity("lost_replacement_missing:" + profileId);
            }
            return null;
        }
        if (!replacement.isJsonPrimitive() || !replacement.getAsJsonPrimitive().isString()) {
            throw integrity("invalid_lost_replacement_type:" + profileId);
        }
        return parseRequiredUuid(replacement.getAsString(), "invalid_lost_replacement_uuid:" + profileId);
    }

    private ProfileStateRow requireProfileState(Connection connection, String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT capture_active, death_active, lost_active, in_coop
                FROM profile_states WHERE profile_id = ? LIMIT 2
                """)) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw integrity("profile_state_missing:" + profileId);
                }
                ProfileStateRow state = new ProfileStateRow(
                        readBoolean(resultSet, "capture_active", "invalid_capture_state:" + profileId),
                        readBoolean(resultSet, "death_active", "invalid_death_state:" + profileId),
                        readBoolean(resultSet, "lost_active", "invalid_lost_state:" + profileId),
                        readBoolean(resultSet, "in_coop", "invalid_coop_state:" + profileId)
                );
                if (resultSet.next()) {
                    throw integrity("multiple_profile_state_rows:" + profileId);
                }
                return state;
            }
        }
    }

    private UUID requireProfileCurrentUuid(Connection connection, String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_npc_uuid FROM npc_profiles WHERE profile_id = ? LIMIT 2")) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw integrity("recovery_profile_missing:" + profileId);
                }
                String rawUuid = resultSet.getString("current_npc_uuid");
                UUID currentUuid = rawUuid == null ? null
                        : parseRequiredUuid(rawUuid, "invalid_profile_current_uuid:" + profileId);
                if (resultSet.next()) {
                    throw integrity("multiple_recovery_profile_rows:" + profileId);
                }
                return currentUuid;
            }
        }
    }

    private TransitionStatus targetOwnershipConflict(Connection connection, String profileId,
                                                      UUID targetUuid) throws SQLException {
        Set<String> owners = uuidOwners(connection, targetUuid);
        if (owners.size() > 1) {
            throw integrity("recovery_target_maps_to_multiple_profiles:" + targetUuid);
        }
        return owners.isEmpty() || owners.contains(profileId)
                ? null
                : TransitionStatus.TARGET_CONFLICT;
    }

    private Set<String> uuidOwners(Connection connection, UUID npcUuid) throws SQLException {
        LinkedHashSet<String> owners = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id FROM npc_profiles WHERE current_npc_uuid = ?
                UNION ALL
                SELECT profile_id FROM npc_uuid_aliases WHERE npc_uuid = ?
                """)) {
            bindUuid(statement, 1, npcUuid);
            bindUuid(statement, 2, npcUuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    owners.add(requireText(
                            resultSet.getString("profile_id"), "blank_recovery_uuid_owner:" + npcUuid));
                }
            }
        }
        return owners;
    }

    private boolean hasActiveCoopConflict(Connection connection, String profileId) throws SQLException {
        return hasUniqueActiveRow(connection,
                "SELECT resident_id FROM managed_coop_residents WHERE profile_id = ? "
                        + "AND active = 1 ORDER BY resident_id LIMIT 2",
                profileId, "multiple_active_managed_residents:")
                || hasUniqueActiveRow(connection,
                "SELECT operation_id FROM coop_lifecycle_operations WHERE profile_id = ? "
                        + "AND active = 1 ORDER BY operation_id LIMIT 2",
                profileId, "multiple_active_coop_lifecycle_operations:");
    }

    private boolean hasUniqueActiveRow(Connection connection, String sql, String profileId,
                                       String duplicatePrefix) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                requireText(resultSet.getString(1), duplicatePrefix + "blank_id:" + profileId);
                if (resultSet.next()) {
                    throw integrity(duplicatePrefix + profileId);
                }
                return true;
            }
        }
    }

    private boolean readBoolean(ResultSet resultSet, String column, String errorPrefix) throws SQLException {
        int value = resultSet.getInt(column);
        if (value != 0 && value != 1) {
            throw integrity(errorPrefix + "=" + value);
        }
        return value == 1;
    }

    private UUID parseRequiredUuid(String raw, String errorPrefix) {
        try {
            return UUID.fromString(raw == null ? "" : raw);
        } catch (IllegalArgumentException exception) {
            throw integrity(errorPrefix, exception);
        }
    }

    private String requireText(String value, String error) {
        if (value == null || value.isBlank()) {
            throw integrity(error);
        }
        return value.trim();
    }

    private void bindUuid(PreparedStatement statement, int index, UUID uuid) throws SQLException {
        statement.setString(index, uuid == null ? null : uuid.toString());
    }

    private NpcRecoveryOperationTransactions.RepositoryIntegrityException integrity(String message) {
        return new NpcRecoveryOperationTransactions.RepositoryIntegrityException(message);
    }

    private NpcRecoveryOperationTransactions.RepositoryIntegrityException integrity(
            String message, Throwable cause) {
        return new NpcRecoveryOperationTransactions.RepositoryIntegrityException(message, cause);
    }

    private record LostSnapshotRow(long snapshotId, @Nonnull JsonObject payload) {
    }

    private record ProfileStateRow(boolean captureActive,
                                   boolean deathActive,
                                   boolean lostActive,
                                   boolean inCoop) {
    }
}
