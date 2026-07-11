package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reads cross-domain identity and lifecycle evidence that blocks a new recovery claim.
 */
final class NpcRecoveryConflictStore {
    boolean sourceMapsToDifferentProfile(@Nonnull Connection connection,
                                         @Nonnull String requestedProfileId,
                                         @Nullable UUID sourceNpcUuid) throws SQLException {
        if (sourceNpcUuid == null) {
            return false;
        }
        LinkedHashSet<String> mappedProfiles = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id FROM npc_profiles WHERE current_npc_uuid = ?
                UNION ALL
                SELECT profile_id FROM npc_uuid_aliases WHERE npc_uuid = ?
                """)) {
            bindUuid(statement, 1, sourceNpcUuid);
            bindUuid(statement, 2, sourceNpcUuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String profileId = resultSet.getString(1);
                    if (profileId == null || profileId.isBlank()) {
                        throw integrity("source_uuid_has_blank_profile:" + sourceNpcUuid);
                    }
                    mappedProfiles.add(profileId);
                }
            }
        }
        if (mappedProfiles.size() > 1) {
            throw integrity("source_uuid_maps_to_multiple_profiles:" + sourceNpcUuid);
        }
        return !mappedProfiles.isEmpty() && !mappedProfiles.contains(requestedProfileId);
    }

    boolean profileStateBlocksRecovery(@Nonnull Connection connection,
                                       @Nonnull String profileId) throws SQLException {
        return profileFlagsBlockRecovery(connection, profileId)
                || hasActiveManagedResident(connection, profileId)
                || hasActiveCoopLifecycleOperation(connection, profileId);
    }

    boolean targetHasCrossDomainEvidence(@Nonnull Connection connection,
                                         @Nonnull UUID targetUuid) throws SQLException {
        return profileReferencesUuid(connection, targetUuid)
                || managedResidentReferencesUuid(connection, targetUuid)
                || managedUuidClaimExists(connection, targetUuid)
                || coopLifecycleReferencesUuid(connection, targetUuid);
    }

    private boolean profileFlagsBlockRecovery(@Nonnull Connection connection,
                                              @Nonnull String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT capture_active, death_active, lost_active, in_coop
                FROM profile_states WHERE profile_id = ? LIMIT 2
                """)) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                boolean captured = readBoolean(resultSet, "capture_active", profileId);
                boolean dead = readBoolean(resultSet, "death_active", profileId);
                readBoolean(resultSet, "lost_active", profileId);
                boolean inCoop = readBoolean(resultSet, "in_coop", profileId);
                if (resultSet.next()) {
                    throw integrity("multiple_profile_state_rows:" + profileId);
                }
                return captured || dead || inCoop;
            }
        }
    }

    private boolean hasActiveManagedResident(@Nonnull Connection connection,
                                             @Nonnull String profileId) throws SQLException {
        return hasAtMostOneActiveRow(
                connection,
                "SELECT resident_id FROM managed_coop_residents "
                        + "WHERE profile_id = ? AND active = 1 ORDER BY resident_id LIMIT 2",
                profileId,
                "multiple_active_managed_residents:"
        );
    }

    private boolean hasActiveCoopLifecycleOperation(@Nonnull Connection connection,
                                                    @Nonnull String profileId) throws SQLException {
        return hasAtMostOneActiveRow(
                connection,
                "SELECT operation_id FROM coop_lifecycle_operations "
                        + "WHERE profile_id = ? AND active = 1 ORDER BY operation_id LIMIT 2",
                profileId,
                "multiple_active_coop_lifecycle_operations:"
        );
    }

    private boolean hasAtMostOneActiveRow(@Nonnull Connection connection,
                                          @Nonnull String sql,
                                          @Nonnull String profileId,
                                          @Nonnull String integrityPrefix) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                if (resultSet.getString(1) == null || resultSet.getString(1).isBlank()) {
                    throw integrity(integrityPrefix + "blank_id:" + profileId);
                }
                if (resultSet.next()) {
                    throw integrity(integrityPrefix + profileId);
                }
                return true;
            }
        }
    }

    private boolean profileReferencesUuid(@Nonnull Connection connection,
                                          @Nonnull UUID targetUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 WHERE EXISTS (
                    SELECT 1 FROM npc_profiles WHERE current_npc_uuid = ?
                ) OR EXISTS (
                    SELECT 1 FROM npc_uuid_aliases WHERE npc_uuid = ?
                )
                """)) {
            bindUuid(statement, 1, targetUuid);
            bindUuid(statement, 2, targetUuid);
            return hasRow(statement);
        }
    }

    private boolean managedResidentReferencesUuid(@Nonnull Connection connection,
                                                  @Nonnull UUID targetUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM managed_coop_residents
                WHERE resident_uuid = ? OR source_npc_uuid = ? OR deployed_npc_uuid = ?
                LIMIT 1
                """)) {
            bindUuid(statement, 1, targetUuid);
            bindUuid(statement, 2, targetUuid);
            bindUuid(statement, 3, targetUuid);
            return hasRow(statement);
        }
    }

    private boolean managedUuidClaimExists(@Nonnull Connection connection,
                                           @Nonnull UUID targetUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM managed_coop_uuid_claims WHERE npc_uuid = ? LIMIT 1")) {
            bindUuid(statement, 1, targetUuid);
            return hasRow(statement);
        }
    }

    private boolean coopLifecycleReferencesUuid(@Nonnull Connection connection,
                                                @Nonnull UUID targetUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM coop_lifecycle_operations
                WHERE planned_target_uuid = ? OR actual_target_uuid = ? LIMIT 1
                """)) {
            bindUuid(statement, 1, targetUuid);
            bindUuid(statement, 2, targetUuid);
            return hasRow(statement);
        }
    }

    private boolean readBoolean(@Nonnull ResultSet resultSet,
                                @Nonnull String column,
                                @Nonnull String profileId) throws SQLException {
        int value = resultSet.getInt(column);
        if (value != 0 && value != 1) {
            throw integrity("invalid_profile_state_boolean:" + profileId + ":" + column + "=" + value);
        }
        return value == 1;
    }

    private boolean hasRow(@Nonnull PreparedStatement statement) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next();
        }
    }

    private void bindUuid(@Nonnull PreparedStatement statement,
                          int index,
                          @Nonnull UUID uuid) throws SQLException {
        statement.setString(index, uuid.toString());
    }

    @Nonnull
    private NpcRecoveryOperationTransactions.RepositoryIntegrityException integrity(
            @Nonnull String message) {
        return new NpcRecoveryOperationTransactions.RepositoryIntegrityException(message);
    }
}
