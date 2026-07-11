package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Atomically makes the sole live UUID alias canonical and clears a stale awaiting-lost state.
 *
 * <p>This repair is deliberately narrower than recovery finalization: it never creates identity,
 * accepts a UUID from another profile, or runs while another durable lifecycle owns the profile.
 * The caller must independently prove that {@code liveNpcUuid} is the profile's sole live alias.
 */
public final class NpcLiveAliasRepairRepository {
    private final PersistenceWriteQueue writeQueue;

    public NpcLiveAliasRepairRepository(@Nonnull PersistenceWriteQueue writeQueue) {
        this.writeQueue = Objects.requireNonNull(writeQueue, "writeQueue");
    }

    /** Queues one optimistic, all-or-nothing identity and lost-state repair. */
    @Nonnull
    public PersistenceWriteQueue.WriteSubmission<RepairResult> repair(@Nonnull RepairRequest request) {
        Objects.requireNonNull(request, "request");
        return writeQueue.submitTracked(
                "npc_live_alias_repair",
                connection -> repairInTransaction(connection, request),
                null
        );
    }

    @Nonnull
    private RepairResult repairInTransaction(@Nonnull Connection connection,
                                             @Nonnull RepairRequest request) throws SQLException {
        ProfileRow profile = loadProfile(connection, request.profileId());
        if (profile == null) {
            return RepairResult.of(RepairStatus.PROFILE_NOT_FOUND, null);
        }
        if (!Objects.equals(profile.currentNpcUuid(), request.expectedCurrentNpcUuid())) {
            return RepairResult.of(RepairStatus.CURRENT_UUID_CONFLICT, profile.currentNpcUuid());
        }
        AliasStatus aliasStatus = validateAliasOwnership(
                connection, request.profileId(), request.liveNpcUuid());
        if (aliasStatus != AliasStatus.OWNED_BY_PROFILE) {
            return RepairResult.of(
                    aliasStatus == AliasStatus.OWNED_ELSEWHERE
                            ? RepairStatus.UUID_OWNERSHIP_CONFLICT
                            : RepairStatus.LIVE_UUID_NOT_ALIAS,
                    profile.currentNpcUuid()
            );
        }
        if (hasBlockingLifecycle(connection, request.profileId())) {
            return RepairResult.of(RepairStatus.LIFECYCLE_CONFLICT, profile.currentNpcUuid());
        }
        ProfileState state = loadProfileState(connection, request.profileId());
        if (state != null && (state.captureActive() || state.deathActive()
                || state.inCoop() || state.coopKey() != null)) {
            return RepairResult.of(RepairStatus.PROFILE_STATE_CONFLICT, profile.currentNpcUuid());
        }

        boolean changed = !request.liveNpcUuid().equals(profile.currentNpcUuid());
        if (changed) {
            remapCurrent(connection, request);
        }
        int clearedSnapshots = deactivateAwaitingLostSnapshot(connection, request.profileId());
        if (clearedSnapshots > 0) {
            clearLostFlag(connection, request.profileId());
        }
        mergeToolLinks(connection, request.profileId(), request.toolIds());
        return new RepairResult(
                changed || clearedSnapshots > 0 ? RepairStatus.APPLIED : RepairStatus.REPLAYED,
                request.liveNpcUuid(),
                clearedSnapshots
        );
    }

    @Nullable
    private ProfileRow loadProfile(@Nonnull Connection connection,
                                   @Nonnull String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_npc_uuid FROM npc_profiles WHERE profile_id = ? LIMIT 2")) {
            statement.setString(1, profileId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                UUID current = parseUuid(rows.getString(1));
                if (rows.next()) {
                    throw new IntegrityException("duplicate_profile:" + profileId);
                }
                return new ProfileRow(current);
            }
        }
    }

    @Nonnull
    private AliasStatus validateAliasOwnership(@Nonnull Connection connection,
                                               @Nonnull String profileId,
                                               @Nonnull UUID liveNpcUuid) throws SQLException {
        TreeSet<String> owners = new TreeSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id FROM npc_profiles WHERE current_npc_uuid = ?
                UNION
                SELECT profile_id FROM npc_uuid_aliases WHERE npc_uuid = ?
                """)) {
            statement.setString(1, liveNpcUuid.toString());
            statement.setString(2, liveNpcUuid.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    owners.add(rows.getString(1));
                }
            }
        }
        if (owners.isEmpty()) {
            return AliasStatus.NOT_AN_ALIAS;
        }
        return owners.size() == 1 && owners.contains(profileId)
                ? AliasStatus.OWNED_BY_PROFILE
                : AliasStatus.OWNED_ELSEWHERE;
    }

    private boolean hasBlockingLifecycle(@Nonnull Connection connection,
                                         @Nonnull String profileId) throws SQLException {
        return hasActiveRow(connection,
                "SELECT 1 FROM managed_coop_residents WHERE profile_id = ? AND active = 1 LIMIT 1",
                profileId)
                || hasActiveRow(connection,
                "SELECT 1 FROM coop_lifecycle_operations WHERE profile_id = ? AND active = 1 LIMIT 1",
                profileId)
                || hasActiveRow(connection,
                "SELECT 1 FROM npc_recovery_operations WHERE profile_id = ? AND active = 1 LIMIT 1",
                profileId);
    }

    private boolean hasActiveRow(@Nonnull Connection connection,
                                 @Nonnull String sql,
                                 @Nonnull String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profileId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    @Nullable
    private ProfileState loadProfileState(@Nonnull Connection connection,
                                          @Nonnull String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT capture_active, death_active, in_coop, coop_key
                FROM profile_states WHERE profile_id = ? LIMIT 2
                """)) {
            statement.setString(1, profileId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                ProfileState result = new ProfileState(
                        readBoolean(rows, 1), readBoolean(rows, 2),
                        readBoolean(rows, 3), normalize(rows.getString(4))
                );
                if (rows.next()) {
                    throw new IntegrityException("duplicate_profile_state:" + profileId);
                }
                return result;
            }
        }
    }

    private void remapCurrent(@Nonnull Connection connection,
                              @Nonnull RepairRequest request) throws SQLException {
        long nowMs = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE npc_profiles
                SET current_npc_uuid = ?, updated_at_ms = ?, last_active_at_ms = ?
                WHERE profile_id = ?
                  AND ((current_npc_uuid IS NULL AND ? IS NULL) OR current_npc_uuid = ?)
                """)) {
            statement.setString(1, request.liveNpcUuid().toString());
            statement.setLong(2, nowMs);
            statement.setLong(3, nowMs);
            statement.setString(4, request.profileId());
            bindUuid(statement, 5, request.expectedCurrentNpcUuid());
            bindUuid(statement, 6, request.expectedCurrentNpcUuid());
            if (statement.executeUpdate() != 1) {
                throw new IntegrityException("concurrent_current_uuid_change:" + request.profileId());
            }
        }
        try (PreparedStatement clear = connection.prepareStatement(
                "UPDATE npc_uuid_aliases SET is_current = 0, mapped_at_ms = ? WHERE profile_id = ?")) {
            clear.setLong(1, nowMs);
            clear.setString(2, request.profileId());
            clear.executeUpdate();
        }
        try (PreparedStatement current = connection.prepareStatement("""
                UPDATE npc_uuid_aliases SET is_current = 1, mapped_at_ms = ?
                WHERE npc_uuid = ? AND profile_id = ?
                """)) {
            current.setLong(1, nowMs);
            current.setString(2, request.liveNpcUuid().toString());
            current.setString(3, request.profileId());
            if (current.executeUpdate() != 1) {
                throw new IntegrityException("live_alias_disappeared:" + request.liveNpcUuid());
            }
        }
    }

    private int deactivateAwaitingLostSnapshot(@Nonnull Connection connection,
                                               @Nonnull String profileId) throws SQLException {
        Long snapshotId = null;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT snapshot_id, json_type(payload_json, '$.replacementNpcUuid')
                FROM npc_snapshots
                WHERE profile_id = ? AND snapshot_type = 'lost' AND is_active = 1
                ORDER BY created_at_ms DESC, snapshot_id LIMIT 2
                """)) {
            statement.setString(1, profileId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return 0;
                }
                snapshotId = rows.getLong(1);
                String replacementType = rows.getString(2);
                if (rows.next()) {
                    throw new IntegrityException("multiple_active_lost_snapshots:" + profileId);
                }
                // A recovered snapshot remains active to suppress a stale original UUID.
                if (replacementType != null) {
                    return 0;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE npc_snapshots SET is_active = 0 WHERE snapshot_id = ? AND is_active = 1")) {
            statement.setLong(1, snapshotId);
            return statement.executeUpdate();
        }
    }

    private void clearLostFlag(@Nonnull Connection connection,
                               @Nonnull String profileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE profile_states SET lost_active = 0, updated_at_ms = ? WHERE profile_id = ?")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, profileId);
            statement.executeUpdate();
        }
    }

    private void mergeToolLinks(@Nonnull Connection connection,
                                @Nonnull String profileId,
                                @Nonnull List<String> toolIds) throws SQLException {
        long nowMs = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO npc_tool_links (profile_id, tool_uuid, link_type, created_at_ms, updated_at_ms)
                VALUES (?, ?, 'profile', ?, ?)
                ON CONFLICT(profile_id, tool_uuid, link_type) DO UPDATE SET
                    updated_at_ms = excluded.updated_at_ms
                """)) {
            for (String toolId : toolIds) {
                statement.setString(1, profileId);
                statement.setString(2, toolId);
                statement.setLong(3, nowMs);
                statement.setLong(4, nowMs);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private boolean readBoolean(@Nonnull ResultSet rows, int column) throws SQLException {
        int value = rows.getInt(column);
        if (value != 0 && value != 1) {
            throw new IntegrityException("invalid_boolean:" + value);
        }
        return value == 1;
    }

    @Nullable
    private UUID parseUuid(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw new IntegrityException("invalid_uuid:" + raw, exception);
        }
    }

    private void bindUuid(@Nonnull PreparedStatement statement, int column,
                          @Nullable UUID value) throws SQLException {
        statement.setString(column, value != null ? value.toString() : null);
    }

    @Nullable
    private static String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public enum RepairStatus {
        APPLIED,
        REPLAYED,
        PROFILE_NOT_FOUND,
        CURRENT_UUID_CONFLICT,
        LIVE_UUID_NOT_ALIAS,
        UUID_OWNERSHIP_CONFLICT,
        PROFILE_STATE_CONFLICT,
        LIFECYCLE_CONFLICT
    }

    public record RepairRequest(@Nonnull String profileId,
                                @Nullable UUID expectedCurrentNpcUuid,
                                @Nonnull UUID liveNpcUuid,
                                @Nonnull List<String> toolIds) {
        public RepairRequest {
            profileId = normalize(profileId);
            if (profileId == null) {
                throw new IllegalArgumentException("profileId is required");
            }
            Objects.requireNonNull(liveNpcUuid, "liveNpcUuid");
            TreeSet<String> canonical = new TreeSet<>();
            if (toolIds != null) {
                for (String toolId : toolIds) {
                    String normalized = normalize(toolId);
                    if (normalized != null) {
                        canonical.add(normalized);
                    }
                }
            }
            toolIds = List.copyOf(canonical);
        }
    }

    public record RepairResult(@Nonnull RepairStatus status,
                               @Nullable UUID currentNpcUuid,
                               int clearedLostSnapshots) {
        @Nonnull
        private static RepairResult of(@Nonnull RepairStatus status,
                                       @Nullable UUID currentNpcUuid) {
            return new RepairResult(status, currentNpcUuid, 0);
        }

        public boolean isSuccess() {
            return status == RepairStatus.APPLIED || status == RepairStatus.REPLAYED;
        }
    }

    private enum AliasStatus {
        OWNED_BY_PROFILE,
        NOT_AN_ALIAS,
        OWNED_ELSEWHERE
    }

    private record ProfileRow(@Nullable UUID currentNpcUuid) {
    }

    private record ProfileState(boolean captureActive,
                                boolean deathActive,
                                boolean inCoop,
                                @Nullable String coopKey) {
    }

    private static final class IntegrityException extends RuntimeException {
        private IntegrityException(@Nonnull String message) {
            super(message);
        }

        private IntegrityException(@Nonnull String message, @Nonnull Throwable cause) {
            super(message, cause);
        }
    }
}
