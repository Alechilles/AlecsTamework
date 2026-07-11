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
import java.util.Set;
import java.util.UUID;

import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryFinalization;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.RecoveryOperation;
import static com.alechilles.alecstamework.persistence.sqlite.NpcRecoveryOperationRepository.TransitionStatus;

/** Verifies that an idempotent recovery replay still matches every finalized durable side effect. */
final class NpcRecoveryFinalizationVerifier {
    private static final String PROFILE_LINK_TYPE = "profile";

    TransitionStatus verify(Connection connection,
                            RecoveryOperation operation,
                            RecoveryFinalization finalization) throws SQLException {
        if (operation.completedAtMs() == 0L || operation.updatedAtMs() != operation.completedAtMs()) {
            return TransitionStatus.STATE_CONFLICT;
        }
        TransitionStatus ownership = targetOwnershipConflict(
                connection, finalization.profileId(), finalization.plannedTargetUuid());
        if (ownership != null) {
            return ownership;
        }
        if (!finalization.plannedTargetUuid().equals(
                requireProfileCurrentUuid(connection, finalization.profileId()))) {
            return TransitionStatus.STATE_CONFLICT;
        }
        ProfileStateRow state = requireProfileState(connection, finalization.profileId());
        if (state.captureActive() || state.deathActive() || !state.lostActive() || state.inCoop()
                || hasActiveCoopConflict(connection, finalization.profileId())) {
            return TransitionStatus.STATE_CONFLICT;
        }
        if (!aliasesMatch(connection, finalization)
                || !lostSnapshotMatches(connection, finalization, operation.completedAtMs())
                || !toolLinksContain(connection, finalization.profileId(), finalization.toolIds())) {
            return TransitionStatus.STATE_CONFLICT;
        }
        return TransitionStatus.REPLAYED;
    }

    private boolean aliasesMatch(Connection connection,
                                 RecoveryFinalization finalization) throws SQLException {
        boolean targetFound = false;
        boolean sourceFound = finalization.sourceNpcUuid() == null;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT npc_uuid, is_current FROM npc_uuid_aliases
                WHERE profile_id = ? ORDER BY npc_uuid
                """)) {
            statement.setString(1, finalization.profileId());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID aliasUuid = parseRequiredUuid(
                            resultSet.getString("npc_uuid"), "invalid_profile_alias_uuid");
                    boolean current = readBoolean(
                            resultSet, "is_current", "invalid_profile_alias_current");
                    if (aliasUuid.equals(finalization.plannedTargetUuid())) {
                        targetFound = current;
                    } else if (current) {
                        return false;
                    }
                    if (aliasUuid.equals(finalization.sourceNpcUuid())) {
                        sourceFound = !current;
                    }
                }
            }
        }
        return targetFound && sourceFound;
    }

    private boolean lostSnapshotMatches(Connection connection,
                                        RecoveryFinalization finalization,
                                        long completedAtMs) throws SQLException {
        JsonObject payload = requireActiveLostPayload(connection, finalization.profileId());
        UUID replacement = readReplacementUuid(payload, finalization.profileId());
        JsonElement recoveredAt = payload.get("recoveredAtMs");
        if (recoveredAt == null || !recoveredAt.isJsonPrimitive()
                || !recoveredAt.getAsJsonPrimitive().isNumber()) {
            throw integrity("invalid_lost_recovered_at:" + finalization.profileId());
        }
        try {
            return finalization.plannedTargetUuid().equals(replacement)
                    && recoveredAt.getAsLong() == completedAtMs;
        } catch (RuntimeException exception) {
            throw integrity("invalid_lost_recovered_at:" + finalization.profileId(), exception);
        }
    }

    private boolean toolLinksContain(Connection connection,
                                     String profileId,
                                     List<String> requiredToolIds) throws SQLException {
        if (requiredToolIds.isEmpty()) {
            return true;
        }
        LinkedHashSet<String> found = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tool_uuid FROM npc_tool_links
                WHERE profile_id = ? AND link_type = ?
                """)) {
            statement.setString(1, profileId);
            statement.setString(2, PROFILE_LINK_TYPE);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    found.add(requireText(
                            resultSet.getString("tool_uuid"), "blank_recovery_tool_link:" + profileId));
                }
            }
        }
        return found.containsAll(requiredToolIds);
    }

    private JsonObject requireActiveLostPayload(Connection connection, String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT snapshot_id, snapshot_version, payload_json
                FROM npc_snapshots
                WHERE profile_id = ? AND snapshot_type = 'lost' AND is_active = 1
                ORDER BY created_at_ms DESC, snapshot_id LIMIT 2
                """)) {
            statement.setString(1, profileId);
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
                return payload;
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

    private UUID readReplacementUuid(JsonObject payload, String profileId) {
        JsonElement replacement = payload.get("replacementNpcUuid");
        if (replacement == null) {
            throw integrity("lost_replacement_missing:" + profileId);
        }
        if (!replacement.isJsonPrimitive() || !replacement.getAsJsonPrimitive().isString()) {
            throw integrity("invalid_lost_replacement_type:" + profileId);
        }
        return parseRequiredUuid(
                replacement.getAsString(), "invalid_lost_replacement_uuid:" + profileId);
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

    private TransitionStatus targetOwnershipConflict(Connection connection,
                                                      String profileId,
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
            statement.setString(1, npcUuid.toString());
            statement.setString(2, npcUuid.toString());
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

    private boolean hasUniqueActiveRow(Connection connection,
                                       String sql,
                                       String profileId,
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

    private NpcRecoveryOperationTransactions.RepositoryIntegrityException integrity(String message) {
        return new NpcRecoveryOperationTransactions.RepositoryIntegrityException(message);
    }

    private NpcRecoveryOperationTransactions.RepositoryIntegrityException integrity(
            String message, Throwable cause) {
        return new NpcRecoveryOperationTransactions.RepositoryIntegrityException(message, cause);
    }

    private record ProfileStateRow(boolean captureActive,
                                   boolean deathActive,
                                   boolean lostActive,
                                   boolean inCoop) {
    }
}
