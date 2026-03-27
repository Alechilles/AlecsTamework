package com.alechilles.alecstamework.persistence.sqlite;

import com.google.gson.JsonObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Canonical NPC profile repository for UUID aliasing, tool links, snapshots, and profile state flags.
 */
public final class NpcProfileRepository {
    public record ProfileUpdate(@Nonnull UUID npcUuid,
                                @Nullable UUID ownerUuid,
                                @Nullable String ownerName,
                                @Nullable String roleId,
                                @Nullable String displayName,
                                @Nullable String customName,
                                @Nullable Boolean tamed,
                                @Nullable String coopId,
                                @Nullable Integer coopSlot,
                                @Nullable String profileJson,
                                @Nullable String[] toolIds) {
    }

    @Nullable
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;

    public NpcProfileRepository(@Nonnull SqliteConnectionManager connectionManager,
                                @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
    }

    public NpcProfileRepository(@Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = null;
        this.writeQueue = writeQueue;
    }

    public boolean upsertAsync(@Nonnull ProfileUpdate update) {
        return writeQueue.submit("npc_profile_upsert", connection -> upsertProfileInTransaction(connection, update));
    }

    public boolean remapCurrentUuidAsync(@Nonnull UUID previousNpcUuid, @Nonnull UUID currentNpcUuid) {
        return writeQueue.submit(
                "npc_profile_remap_uuid",
                connection -> remapCurrentUuidInTransaction(connection, previousNpcUuid, currentNpcUuid)
        );
    }

    public boolean pruneInactiveSnapshotHistoryAsync(long cutoffMs) {
        return writeQueue.submit(
                "snapshot_history_prune",
                connection -> pruneInactiveSnapshotHistoryInTransaction(connection, cutoffMs)
        );
    }

    @Nullable
    public String resolveProfileId(@Nonnull UUID npcUuid) {
        if (connectionManager == null) {
            return null;
        }
        try (Connection connection = connectionManager.openConnection()) {
            return resolveProfileIdInTransaction(connection, npcUuid);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    String resolveProfileIdInTransaction(@Nonnull Connection connection, @Nonnull UUID npcUuid) throws Exception {
        String npcUuidString = npcUuid.toString();
        String currentProfileId = findProfileIdByCurrentUuidInTransaction(connection, npcUuidString);
        if (currentProfileId != null && !currentProfileId.isBlank()) {
            upsertAliasInTransaction(connection, npcUuidString, currentProfileId, true, System.currentTimeMillis());
            return currentProfileId;
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT profile_id FROM npc_uuid_aliases WHERE npc_uuid = ? LIMIT 1"
        )) {
            statement.setString(1, npcUuidString);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("profile_id");
                }
            }
        }
        return null;
    }

    @Nonnull
    String resolveOrCreateProfileIdInTransaction(@Nonnull Connection connection, @Nonnull UUID npcUuid) throws Exception {
        String resolved = resolveProfileIdInTransaction(connection, npcUuid);
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }

        String profileId = UUID.randomUUID().toString();
        long nowMs = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO npc_profiles (
                    profile_id, current_npc_uuid, owner_uuid, display_name, role_id,
                    state_json, state_hash, last_world_name, created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
        )) {
            statement.setString(1, profileId);
            statement.setString(2, npcUuid.toString());
            statement.setString(3, null);
            statement.setString(4, null);
            statement.setString(5, null);
            statement.setString(6, null);
            statement.setString(7, null);
            statement.setString(8, null);
            statement.setLong(9, nowMs);
            statement.setLong(10, nowMs);
            statement.setLong(11, nowMs);
            statement.executeUpdate();
        }
        upsertAliasInTransaction(connection, npcUuid.toString(), profileId, true, nowMs);
        return profileId;
    }

    void upsertProfileInTransaction(@Nonnull Connection connection, @Nonnull ProfileUpdate update) throws Exception {
        String npcUuidString = update.npcUuid().toString();
        String profileId = resolveOrCreateProfileIdInTransaction(connection, update.npcUuid());
        long nowMs = System.currentTimeMillis();
        String currentNpcUuid = resolveEffectiveCurrentUuidForUpsert(connection, profileId, npcUuidString);
        JsonObject state = buildStateJson(update);
        String stateJson = state.size() > 0 ? state.toString() : null;
        String stateHash = stateJson != null ? Integer.toHexString(stateJson.hashCode()) : null;

        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO npc_profiles (
                    profile_id, current_npc_uuid, owner_uuid, display_name, role_id,
                    state_json, state_hash, last_world_name, created_at_ms, updated_at_ms, last_active_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(profile_id) DO UPDATE SET
                    current_npc_uuid = excluded.current_npc_uuid,
                    owner_uuid = COALESCE(excluded.owner_uuid, npc_profiles.owner_uuid),
                    display_name = COALESCE(excluded.display_name, npc_profiles.display_name),
                    role_id = COALESCE(excluded.role_id, npc_profiles.role_id),
                    state_json = COALESCE(excluded.state_json, npc_profiles.state_json),
                    state_hash = COALESCE(excluded.state_hash, npc_profiles.state_hash),
                    last_world_name = COALESCE(excluded.last_world_name, npc_profiles.last_world_name),
                    updated_at_ms = excluded.updated_at_ms,
                    last_active_at_ms = excluded.last_active_at_ms
                """
        )) {
            statement.setString(1, profileId);
            statement.setString(2, currentNpcUuid);
            SqliteValueCodec.bindUuid(statement, 3, update.ownerUuid());
            statement.setString(4, trimToNull(update.displayName()));
            statement.setString(5, trimToNull(update.roleId()));
            statement.setString(6, stateJson);
            statement.setString(7, stateHash);
            statement.setString(8, null);
            statement.setLong(9, nowMs);
            statement.setLong(10, nowMs);
            statement.setLong(11, nowMs);
            statement.executeUpdate();
        }

        if (!Objects.equals(currentNpcUuid, npcUuidString)) {
            upsertAliasInTransaction(connection, npcUuidString, profileId, false, nowMs);
        }
        setAliasCurrentInTransaction(connection, profileId, currentNpcUuid, nowMs);
        replaceToolLinksInTransaction(connection, profileId, "profile", update.toolIds());
    }

    void remapCurrentUuidInTransaction(@Nonnull Connection connection,
                                       @Nonnull UUID previousNpcUuid,
                                       @Nonnull UUID currentNpcUuid) throws Exception {
        long nowMs = System.currentTimeMillis();
        String currentUuidString = currentNpcUuid.toString();
        String profileId = resolveProfileIdInTransaction(connection, previousNpcUuid);
        String currentHolderProfileId = findProfileIdByCurrentUuidInTransaction(connection, currentUuidString);
        if (profileId == null || profileId.isBlank()) {
            if (currentHolderProfileId != null && !currentHolderProfileId.isBlank()) {
                profileId = currentHolderProfileId;
            } else {
                profileId = resolveOrCreateProfileIdInTransaction(connection, currentNpcUuid);
            }
        } else if (currentHolderProfileId != null
                && !currentHolderProfileId.isBlank()
                && !currentHolderProfileId.equals(profileId)) {
            clearCurrentUuidForProfileInTransaction(connection, currentHolderProfileId, nowMs);
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE npc_profiles SET current_npc_uuid = ?, updated_at_ms = ?, last_active_at_ms = ? WHERE profile_id = ?"
        )) {
            statement.setString(1, currentUuidString);
            statement.setLong(2, nowMs);
            statement.setLong(3, nowMs);
            statement.setString(4, profileId);
            statement.executeUpdate();
        }

        upsertAliasInTransaction(connection, previousNpcUuid.toString(), profileId, false, nowMs);
        setAliasCurrentInTransaction(connection, profileId, currentUuidString, nowMs);
    }

    void replaceToolLinksInTransaction(@Nonnull Connection connection,
                                       @Nonnull String profileId,
                                       @Nonnull String linkType,
                                       @Nullable String[] toolIds) throws Exception {
        if (linkType.isBlank()) {
            return;
        }
        if (toolIds == null) {
            return;
        }

        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM npc_tool_links WHERE profile_id = ? AND link_type = ?"
        )) {
            delete.setString(1, profileId);
            delete.setString(2, linkType);
            delete.executeUpdate();
        }

        String[] sanitized = sanitizeToolIds(toolIds);
        if (sanitized.length == 0) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO npc_tool_links (profile_id, tool_uuid, link_type, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(profile_id, tool_uuid, link_type) DO UPDATE SET
                    updated_at_ms = excluded.updated_at_ms
                """
        )) {
            for (String toolId : sanitized) {
                insert.setString(1, profileId);
                insert.setString(2, toolId);
                insert.setString(3, linkType);
                insert.setLong(4, nowMs);
                insert.setLong(5, nowMs);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    @Nonnull
    String[] loadToolLinks(@Nonnull Connection connection,
                           @Nonnull String profileId,
                           @Nonnull String linkType) throws Exception {
        LinkedHashSet<String> toolIds = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT tool_uuid
                FROM npc_tool_links
                WHERE profile_id = ? AND link_type = ?
                ORDER BY tool_uuid
                """
        )) {
            statement.setString(1, profileId);
            statement.setString(2, linkType);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String toolId = trimToNull(rs.getString("tool_uuid"));
                    if (toolId != null) {
                        toolIds.add(toolId);
                    }
                }
            }
        }
        return toolIds.toArray(new String[0]);
    }

    void setActiveSnapshotInTransaction(@Nonnull Connection connection,
                                        @Nonnull String profileId,
                                        @Nonnull String snapshotType,
                                        @Nullable String payloadJson,
                                        long createdAtMs) throws Exception {
        String normalizedPayload = payloadJson != null ? payloadJson : "{}";
        Integer activeVersion = null;
        String activePayload = null;
        try (PreparedStatement active = connection.prepareStatement(
                """
                SELECT snapshot_version, payload_json
                FROM npc_snapshots
                WHERE profile_id = ? AND snapshot_type = ? AND is_active = 1
                ORDER BY snapshot_version DESC
                LIMIT 1
                """
        )) {
            active.setString(1, profileId);
            active.setString(2, snapshotType);
            try (ResultSet rs = active.executeQuery()) {
                if (rs.next()) {
                    activeVersion = rs.getInt("snapshot_version");
                    activePayload = rs.getString("payload_json");
                }
            }
        }

        if (activeVersion != null && Objects.equals(activePayload, normalizedPayload)) {
            return;
        }

        if (activeVersion != null) {
            try (PreparedStatement deactivate = connection.prepareStatement(
                    """
                    UPDATE npc_snapshots
                    SET is_active = 0
                    WHERE profile_id = ? AND snapshot_type = ? AND is_active = 1
                    """
            )) {
                deactivate.setString(1, profileId);
                deactivate.setString(2, snapshotType);
                deactivate.executeUpdate();
            }
        }

        int nextVersion = 1;
        if (activeVersion != null) {
            nextVersion = Math.max(1, activeVersion + 1);
        } else {
            try (PreparedStatement versionQuery = connection.prepareStatement(
                    "SELECT COALESCE(MAX(snapshot_version), 0) + 1 AS next_version FROM npc_snapshots WHERE profile_id = ? AND snapshot_type = ?"
            )) {
                versionQuery.setString(1, profileId);
                versionQuery.setString(2, snapshotType);
                try (ResultSet rs = versionQuery.executeQuery()) {
                    if (rs.next()) {
                        nextVersion = Math.max(1, rs.getInt("next_version"));
                    }
                }
            }
        }

        try (PreparedStatement insert = connection.prepareStatement(
                """
                INSERT INTO npc_snapshots (
                    profile_id, snapshot_type, snapshot_version, payload_json, is_active, created_at_ms
                ) VALUES (?, ?, ?, ?, 1, ?)
                """
        )) {
            insert.setString(1, profileId);
            insert.setString(2, snapshotType);
            insert.setInt(3, nextVersion);
            insert.setString(4, normalizedPayload);
            insert.setLong(5, createdAtMs > 0L ? createdAtMs : System.currentTimeMillis());
            insert.executeUpdate();
        }
    }

    void deactivateSnapshotTypeInTransaction(@Nonnull Connection connection,
                                             @Nonnull String profileId,
                                             @Nonnull String snapshotType) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE npc_snapshots SET is_active = 0 WHERE profile_id = ? AND snapshot_type = ? AND is_active = 1"
        )) {
            statement.setString(1, profileId);
            statement.setString(2, snapshotType);
            statement.executeUpdate();
        }
    }

    void setProfileStateInTransaction(@Nonnull Connection connection,
                                      @Nonnull String profileId,
                                      @Nullable Boolean captureActive,
                                      @Nullable Boolean deathActive,
                                      @Nullable Boolean lostActive,
                                      @Nullable Boolean inCoop,
                                      @Nullable String coopKey) throws Exception {
        long nowMs = System.currentTimeMillis();
        try (PreparedStatement ensure = connection.prepareStatement(
                """
                INSERT INTO profile_states (
                    profile_id, capture_active, death_active, lost_active, in_coop, coop_key, updated_at_ms
                ) VALUES (?, 0, 0, 0, 0, NULL, ?)
                ON CONFLICT(profile_id) DO NOTHING
                """
        )) {
            ensure.setString(1, profileId);
            ensure.setLong(2, nowMs);
            ensure.executeUpdate();
        }

        if (captureActive != null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE profile_states SET capture_active = ?, updated_at_ms = ? WHERE profile_id = ?"
            )) {
                statement.setInt(1, captureActive ? 1 : 0);
                statement.setLong(2, nowMs);
                statement.setString(3, profileId);
                statement.executeUpdate();
            }
        }
        if (deathActive != null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE profile_states SET death_active = ?, updated_at_ms = ? WHERE profile_id = ?"
            )) {
                statement.setInt(1, deathActive ? 1 : 0);
                statement.setLong(2, nowMs);
                statement.setString(3, profileId);
                statement.executeUpdate();
            }
        }
        if (lostActive != null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE profile_states SET lost_active = ?, updated_at_ms = ? WHERE profile_id = ?"
            )) {
                statement.setInt(1, lostActive ? 1 : 0);
                statement.setLong(2, nowMs);
                statement.setString(3, profileId);
                statement.executeUpdate();
            }
        }

        if (inCoop != null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE profile_states
                    SET in_coop = ?, coop_key = ?, updated_at_ms = ?
                    WHERE profile_id = ?
                    """
            )) {
                statement.setInt(1, inCoop ? 1 : 0);
                statement.setString(2, trimToNull(coopKey));
                statement.setLong(3, nowMs);
                statement.setString(4, profileId);
                statement.executeUpdate();
            }
        } else if (coopKey != null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE profile_states SET coop_key = ?, updated_at_ms = ? WHERE profile_id = ?"
            )) {
                statement.setString(1, trimToNull(coopKey));
                statement.setLong(2, nowMs);
                statement.setString(3, profileId);
                statement.executeUpdate();
            }
        }
    }

    void pruneInactiveSnapshotHistoryInTransaction(@Nonnull Connection connection, long cutoffMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM npc_snapshots WHERE is_active = 0 AND created_at_ms < ?"
        )) {
            statement.setLong(1, cutoffMs);
            statement.executeUpdate();
        }
    }

    private void setAliasCurrentInTransaction(@Nonnull Connection connection,
                                              @Nonnull String profileId,
                                              @Nonnull String npcUuid,
                                              long mappedAtMs) throws Exception {
        upsertAliasInTransaction(connection, npcUuid, profileId, true, mappedAtMs);
        try (PreparedStatement clearOthers = connection.prepareStatement(
                "UPDATE npc_uuid_aliases SET is_current = 0, mapped_at_ms = ? WHERE profile_id = ? AND npc_uuid <> ?"
        )) {
            clearOthers.setLong(1, mappedAtMs);
            clearOthers.setString(2, profileId);
            clearOthers.setString(3, npcUuid);
            clearOthers.executeUpdate();
        }
    }

    @Nullable
    private String findProfileIdByCurrentUuidInTransaction(@Nonnull Connection connection,
                                                           @Nonnull String npcUuid) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT profile_id FROM npc_profiles WHERE current_npc_uuid = ? LIMIT 1"
        )) {
            statement.setString(1, npcUuid);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String profileId = rs.getString("profile_id");
                return profileId == null || profileId.isBlank() ? null : profileId;
            }
        }
    }

    @Nullable
    private String findCurrentNpcUuidForProfileInTransaction(@Nonnull Connection connection,
                                                             @Nonnull String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_npc_uuid FROM npc_profiles WHERE profile_id = ? LIMIT 1"
        )) {
            statement.setString(1, profileId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return trimToNull(rs.getString("current_npc_uuid"));
            }
        }
    }

    @Nonnull
    private String resolveEffectiveCurrentUuidForUpsert(@Nonnull Connection connection,
                                                        @Nonnull String profileId,
                                                        @Nonnull String candidateNpcUuid) throws Exception {
        String currentNpcUuid = findCurrentNpcUuidForProfileInTransaction(connection, profileId);
        if (currentNpcUuid == null || currentNpcUuid.equals(candidateNpcUuid)) {
            return candidateNpcUuid;
        }
        String currentHolder = findProfileIdByCurrentUuidInTransaction(connection, candidateNpcUuid);
        if (currentHolder != null && currentHolder.equals(profileId)) {
            return candidateNpcUuid;
        }
        return currentNpcUuid;
    }

    private void clearCurrentUuidForProfileInTransaction(@Nonnull Connection connection,
                                                         @Nonnull String profileId,
                                                         long nowMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE npc_profiles SET current_npc_uuid = NULL, updated_at_ms = ?, last_active_at_ms = ? WHERE profile_id = ?"
        )) {
            statement.setLong(1, nowMs);
            statement.setLong(2, nowMs);
            statement.setString(3, profileId);
            statement.executeUpdate();
        }
    }

    private void upsertAliasInTransaction(@Nonnull Connection connection,
                                          @Nonnull String npcUuid,
                                          @Nonnull String profileId,
                                          boolean isCurrent,
                                          long mappedAtMs) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO npc_uuid_aliases (npc_uuid, profile_id, is_current, mapped_at_ms)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(npc_uuid) DO UPDATE SET
                    profile_id = excluded.profile_id,
                    is_current = excluded.is_current,
                    mapped_at_ms = excluded.mapped_at_ms
                """
        )) {
            statement.setString(1, npcUuid);
            statement.setString(2, profileId);
            statement.setInt(3, isCurrent ? 1 : 0);
            statement.setLong(4, mappedAtMs);
            statement.executeUpdate();
        }
    }

    @Nonnull
    private JsonObject buildStateJson(@Nonnull ProfileUpdate update) {
        JsonObject state = new JsonObject();
        putString(state, "owner_name", update.ownerName());
        putString(state, "custom_name", update.customName());
        putBoolean(state, "tamed", update.tamed());
        putString(state, "coop_id", update.coopId());
        if (update.coopSlot() != null) {
            state.addProperty("coop_slot", update.coopSlot());
        }
        putString(state, "profile_json", update.profileJson());
        return state;
    }

    private void putString(@Nonnull JsonObject object, @Nonnull String key, @Nullable String value) {
        String normalized = trimToNull(value);
        if (normalized != null) {
            object.addProperty(key, normalized);
        }
    }

    private void putBoolean(@Nonnull JsonObject object, @Nonnull String key, @Nullable Boolean value) {
        if (value != null) {
            object.addProperty(key, value);
        }
    }

    @Nonnull
    private String[] sanitizeToolIds(@Nullable String[] toolIds) {
        if (toolIds == null || toolIds.length == 0) {
            return new String[0];
        }
        return Arrays.stream(toolIds)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toArray(String[]::new);
    }

    @Nullable
    private String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
