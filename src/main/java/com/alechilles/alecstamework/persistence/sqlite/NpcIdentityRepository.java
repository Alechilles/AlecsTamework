package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reads one canonical NPC profile and every durable state that can suppress replacement spawning.
 *
 * <p>This repository is intended for command/event boundaries, not per-tick polling. SQL and
 * integrity failures remain explicit so callers cannot mistake an unreadable identity for a
 * missing NPC.
 */
public final class NpcIdentityRepository {
    private final SqliteConnectionManager connectionManager;

    public NpcIdentityRepository(@Nonnull SqliteConnectionManager connectionManager) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
    }

    /** Resolves a profile ID and/or historical UUID without creating or remapping identity. */
    @Nonnull
    public IdentityLoadResult load(@Nullable String profileId, @Nullable UUID historicalUuid) {
        String normalizedProfileId = normalize(profileId);
        if (normalizedProfileId == null && historicalUuid == null) {
            return IdentityLoadResult.failed("identity_input_required", null);
        }
        try (Connection connection = connectionManager.openConnection()) {
            return load(connection, normalizedProfileId, historicalUuid);
        } catch (SQLException | IdentityIntegrityException exception) {
            return IdentityLoadResult.failed(exception.getMessage(), exception);
        }
    }

    @Nonnull
    private IdentityLoadResult load(@Nonnull Connection connection,
                                    @Nullable String requestedProfileId,
                                    @Nullable UUID historicalUuid) throws SQLException {
        String uuidProfileId = historicalUuid == null ? null : resolveProfileId(connection, historicalUuid);
        if (requestedProfileId != null && uuidProfileId != null
                && !requestedProfileId.equals(uuidProfileId)) {
            return IdentityLoadResult.conflict(requestedProfileId, uuidProfileId);
        }
        String resolvedProfileId = requestedProfileId != null ? requestedProfileId : uuidProfileId;
        if (resolvedProfileId == null) {
            return IdentityLoadResult.notFound();
        }
        UUID currentUuid = loadCurrentUuid(connection, resolvedProfileId);
        if (currentUuid == null && !profileExists(connection, resolvedProfileId)) {
            return requestedProfileId != null
                    ? IdentityLoadResult.failed("profile_id_not_found:" + resolvedProfileId, null)
                    : IdentityLoadResult.notFound();
        }
        List<UUID> aliases = loadAliases(connection, resolvedProfileId);
        verifyCurrentAlias(resolvedProfileId, currentUuid, aliases);
        ProfileFlags flags = loadFlags(connection, resolvedProfileId);
        ManagedAssignment assignment = loadManagedAssignment(connection, resolvedProfileId);
        ActiveRecovery recovery = loadActiveRecovery(connection, resolvedProfileId);
        boolean historicalUuidKnown = historicalUuid == null || aliases.contains(historicalUuid);
        return IdentityLoadResult.found(new ProfileIdentity(
                resolvedProfileId,
                currentUuid,
                aliases,
                historicalUuidKnown,
                flags,
                assignment,
                recovery
        ));
    }

    @Nullable
    private String resolveProfileId(@Nonnull Connection connection, @Nonnull UUID npcUuid)
            throws SQLException {
        String current = querySingleProfileId(
                connection,
                "SELECT profile_id FROM npc_profiles WHERE current_npc_uuid = ? LIMIT 2",
                npcUuid
        );
        String alias = querySingleProfileId(
                connection,
                "SELECT profile_id FROM npc_uuid_aliases WHERE npc_uuid = ? LIMIT 2",
                npcUuid
        );
        if (current != null && alias != null && !current.equals(alias)) {
            throw new IdentityIntegrityException("uuid_maps_to_multiple_profiles:" + npcUuid);
        }
        return current != null ? current : alias;
    }

    @Nullable
    private String querySingleProfileId(@Nonnull Connection connection,
                                        @Nonnull String sql,
                                        @Nonnull UUID npcUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, npcUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                String profileId = resultSet.getString(1);
                if (resultSet.next()) {
                    throw new IdentityIntegrityException("uuid_has_multiple_profile_rows:" + npcUuid);
                }
                return profileId;
            }
        }
    }

    private boolean profileExists(@Nonnull Connection connection, @Nonnull String profileId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM npc_profiles WHERE profile_id = ? LIMIT 1")) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    @Nullable
    private UUID loadCurrentUuid(@Nonnull Connection connection, @Nonnull String profileId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_npc_uuid FROM npc_profiles WHERE profile_id = ? LIMIT 1")) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? parseUuid(resultSet.getString(1), "current_npc_uuid") : null;
            }
        }
    }

    @Nonnull
    private List<UUID> loadAliases(@Nonnull Connection connection, @Nonnull String profileId)
            throws SQLException {
        ArrayList<UUID> aliases = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT npc_uuid FROM npc_uuid_aliases
                WHERE profile_id = ? ORDER BY is_current DESC, mapped_at_ms DESC, npc_uuid
                """)) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    aliases.add(parseRequiredUuid(resultSet.getString(1), "alias_uuid"));
                }
            }
        }
        return List.copyOf(aliases);
    }

    private void verifyCurrentAlias(@Nonnull String profileId,
                                    @Nullable UUID currentUuid,
                                    @Nonnull List<UUID> aliases) {
        if (currentUuid != null && !aliases.contains(currentUuid)) {
            throw new IdentityIntegrityException("current_uuid_missing_alias:" + profileId);
        }
    }

    @Nonnull
    private ProfileFlags loadFlags(@Nonnull Connection connection, @Nonnull String profileId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT capture_active, death_active, lost_active, in_coop, coop_key
                FROM profile_states WHERE profile_id = ? LIMIT 1
                """)) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return ProfileFlags.EMPTY;
                }
                return new ProfileFlags(
                        readBoolean(resultSet, "capture_active"),
                        readBoolean(resultSet, "death_active"),
                        readBoolean(resultSet, "lost_active"),
                        readBoolean(resultSet, "in_coop"),
                        resultSet.getString("coop_key")
                );
            }
        }
    }

    @Nullable
    private ManagedAssignment loadManagedAssignment(@Nonnull Connection connection,
                                                    @Nonnull String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT resident_id, authority_id, resident_slot, resident_uuid,
                       source_npc_uuid, deployed_npc_uuid, state, generation
                FROM managed_coop_residents
                WHERE profile_id = ? AND active = 1 ORDER BY created_at_ms, resident_id LIMIT 2
                """)) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                ManagedAssignment assignment = new ManagedAssignment(
                        resultSet.getString("resident_id"),
                        resultSet.getString("authority_id"),
                        resultSet.getInt("resident_slot"),
                        parseRequiredUuid(resultSet.getString("resident_uuid"), "resident_uuid"),
                        parseUuid(resultSet.getString("source_npc_uuid"), "source_npc_uuid"),
                        parseUuid(resultSet.getString("deployed_npc_uuid"), "deployed_npc_uuid"),
                        parseManagedState(resultSet.getString("state")),
                        resultSet.getLong("generation")
                );
                if (resultSet.next()) {
                    throw new IdentityIntegrityException("multiple_active_managed_assignments:" + profileId);
                }
                return assignment;
            }
        }
    }

    @Nullable
    private ActiveRecovery loadActiveRecovery(@Nonnull Connection connection,
                                              @Nonnull String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, state, planned_target_uuid, actual_target_uuid, generation
                FROM npc_recovery_operations
                WHERE profile_id = ? AND active = 1 ORDER BY created_at_ms, operation_id LIMIT 2
                """)) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                ActiveRecovery recovery = new ActiveRecovery(
                        resultSet.getString("operation_id"),
                        parseRecoveryState(resultSet.getString("state")),
                        parseUuid(resultSet.getString("planned_target_uuid"), "planned_target_uuid"),
                        parseUuid(resultSet.getString("actual_target_uuid"), "actual_target_uuid"),
                        resultSet.getLong("generation")
                );
                if (resultSet.next()) {
                    throw new IdentityIntegrityException("multiple_active_recovery_operations:" + profileId);
                }
                return recovery;
            }
        }
    }

    private boolean readBoolean(@Nonnull ResultSet resultSet, @Nonnull String column)
            throws SQLException {
        int value = resultSet.getInt(column);
        if (value != 0 && value != 1) {
            throw new IdentityIntegrityException("invalid_boolean:" + column + "=" + value);
        }
        return value == 1;
    }

    @Nonnull
    private ManagedCoopResidentRepository.ResidentState parseManagedState(@Nullable String value) {
        try {
            return ManagedCoopResidentRepository.ResidentState.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException exception) {
            throw new IdentityIntegrityException("unknown_managed_resident_state:" + value, exception);
        }
    }

    @Nonnull
    private NpcRecoveryOperationRepository.RecoveryState parseRecoveryState(@Nullable String value) {
        try {
            return NpcRecoveryOperationRepository.RecoveryState.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException exception) {
            throw new IdentityIntegrityException("unknown_recovery_state:" + value, exception);
        }
    }

    @Nullable
    private UUID parseUuid(@Nullable String value, @Nonnull String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IdentityIntegrityException("invalid_uuid:" + field, exception);
        }
    }

    @Nonnull
    private UUID parseRequiredUuid(@Nullable String value, @Nonnull String field) {
        UUID parsed = parseUuid(value, field);
        if (parsed == null) {
            throw new IdentityIntegrityException("missing_uuid:" + field);
        }
        return parsed;
    }

    @Nullable
    private String normalize(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public enum LoadStatus {
        FOUND,
        NOT_FOUND,
        CONFLICT,
        FAILED
    }

    public record ProfileFlags(boolean captured,
                               boolean dead,
                               boolean lost,
                               boolean legacyInCoop,
                               @Nullable String legacyCoopKey) {
        private static final ProfileFlags EMPTY = new ProfileFlags(false, false, false, false, null);
    }

    public record ManagedAssignment(@Nonnull String residentId,
                                    @Nonnull String authorityId,
                                    int residentSlot,
                                    @Nonnull UUID residentUuid,
                                    @Nullable UUID sourceNpcUuid,
                                    @Nullable UUID deployedNpcUuid,
                                    @Nonnull ManagedCoopResidentRepository.ResidentState state,
                                    long generation) {
    }

    public record ActiveRecovery(@Nonnull String operationId,
                                 @Nonnull NpcRecoveryOperationRepository.RecoveryState state,
                                 @Nullable UUID plannedTargetUuid,
                                 @Nullable UUID actualTargetUuid,
                                 long generation) {
    }

    public record ProfileIdentity(@Nonnull String profileId,
                                  @Nullable UUID currentNpcUuid,
                                  @Nonnull List<UUID> aliases,
                                  boolean historicalUuidKnown,
                                  @Nonnull ProfileFlags flags,
                                  @Nullable ManagedAssignment managedAssignment,
                                  @Nullable ActiveRecovery activeRecovery) {
        public ProfileIdentity {
            aliases = List.copyOf(aliases);
        }

        public boolean replacementSuppressedByDurableState() {
            return flags.captured() || flags.dead() || flags.lost() || flags.legacyInCoop()
                    || managedAssignment != null || activeRecovery != null;
        }
    }

    public record IdentityLoadResult(@Nonnull LoadStatus status,
                                     @Nullable ProfileIdentity identity,
                                     @Nullable String requestedProfileId,
                                     @Nullable String uuidProfileId,
                                     @Nullable String failureReason,
                                     @Nullable Throwable failure) {
        @Nonnull
        private static IdentityLoadResult found(@Nonnull ProfileIdentity identity) {
            return new IdentityLoadResult(LoadStatus.FOUND, identity, null, null, null, null);
        }

        @Nonnull
        private static IdentityLoadResult notFound() {
            return new IdentityLoadResult(LoadStatus.NOT_FOUND, null, null, null, null, null);
        }

        @Nonnull
        private static IdentityLoadResult conflict(@Nonnull String requested, @Nonnull String resolved) {
            return new IdentityLoadResult(LoadStatus.CONFLICT, null, requested, resolved,
                    "profile_and_uuid_resolve_differently", null);
        }

        @Nonnull
        private static IdentityLoadResult failed(@Nullable String reason, @Nullable Throwable failure) {
            String resolvedReason = reason == null || reason.isBlank() ? "identity_read_failed" : reason;
            return new IdentityLoadResult(LoadStatus.FAILED, null, null, null,
                    resolvedReason, failure);
        }
    }

    private static final class IdentityIntegrityException extends RuntimeException {
        private IdentityIntegrityException(@Nonnull String message) {
            super(message);
        }

        private IdentityIntegrityException(@Nonnull String message, @Nonnull Throwable cause) {
            super(message, cause);
        }
    }
}
