package com.alechilles.alecstamework.persistence.sqlite;

import com.google.gson.JsonObject;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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

    public record ProfileRecord(@Nonnull String profileId,
                                @Nullable UUID currentNpcUuid,
                                @Nullable UUID ownerUuid,
                                @Nullable String ownerName,
                                @Nullable String roleId,
                                @Nullable String displayName,
                                @Nullable String customName,
                                @Nullable Boolean tamed,
                                @Nullable String coopId,
                                @Nullable Integer coopSlot,
                                @Nonnull String[] toolIds,
                                @Nonnull String[] activeSnapshotTypes,
                                long updatedAtMs) {
    }

    @Nullable
    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    @Nullable
    private volatile PersistenceChangeObserver changeObserver;

    public NpcProfileRepository(@Nonnull SqliteConnectionManager connectionManager,
                                @Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
    }

    public NpcProfileRepository(@Nonnull PersistenceWriteQueue writeQueue) {
        this.connectionManager = null;
        this.writeQueue = writeQueue;
    }

    public void setChangeObserver(@Nullable PersistenceChangeObserver changeObserver) {
        this.changeObserver = changeObserver;
    }

    public boolean upsertAsync(@Nonnull ProfileUpdate update) {
        ProfileOwnerMutation ownerMutation = update.ownerUuid() == null
                ? ProfileOwnerMutation.unchanged()
                : ProfileOwnerMutation.set(update.ownerUuid());
        return upsertAsync(update, ownerMutation, "npc_profile_upsert");
    }

    /** Snapshot metadata may enrich a profile but cannot become an ownership authority. */
    public boolean upsertSnapshotAsync(@Nonnull ProfileUpdate update) {
        return upsertAsync(update, ProfileOwnerMutation.unchanged(), "npc_profile_snapshot_upsert");
    }

    private boolean upsertAsync(@Nonnull ProfileUpdate update,
                                @Nonnull ProfileOwnerMutation ownerMutation,
                                @Nonnull String operation) {
        AtomicReference<ProfileRecord> beforeRef = new AtomicReference<>();
        AtomicReference<ProfileRecord> afterRef = new AtomicReference<>();
        return writeQueue.submit(
                operation,
                connection -> {
                    beforeRef.set(loadProfileByNpcUuidInTransaction(connection, update.npcUuid()));
                    upsertProfileInTransaction(connection, update, ownerMutation);
                    String profileId = resolveProfileIdInTransaction(connection, update.npcUuid());
                    afterRef.set(profileId != null ? loadProfileByIdInTransaction(connection, profileId) : null);
                },
                () -> notifyProfileChanged(beforeRef.get(), afterRef.get())
        );
    }

    public boolean remapCurrentUuidAsync(@Nonnull UUID previousNpcUuid, @Nonnull UUID currentNpcUuid) {
        return writeQueue.submit(
                "npc_profile_remap_uuid",
                connection -> remapCurrentUuidInTransaction(connection, previousNpcUuid, currentNpcUuid)
        );
    }

    public boolean deleteProfileTreeAsync(@Nonnull UUID npcUuid) {
        AtomicReference<ProfileRecord> beforeRef = new AtomicReference<>();
        return writeQueue.submit(
                "npc_profile_delete",
                connection -> {
                    beforeRef.set(loadProfileByNpcUuidInTransaction(connection, npcUuid));
                    deleteProfileTreeInTransaction(connection, npcUuid);
                },
                () -> notifyProfileChanged(beforeRef.get(), null)
        );
    }

    public boolean pruneInactiveSnapshotHistoryAsync(long cutoffMs) {
        return pruneInactiveSnapshotHistoryAsync(cutoffMs, 20);
    }

    public boolean pruneInactiveSnapshotHistoryAsync(long cutoffMs, int maxInactivePerProfileType) {
        int safeMaxInactivePerProfileType = Math.max(1, maxInactivePerProfileType);
        return writeQueue.submit(
                "snapshot_history_prune",
                connection -> pruneInactiveSnapshotHistoryInTransaction(
                        connection,
                        cutoffMs,
                        safeMaxInactivePerProfileType
                )
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
    public ProfileRecord loadProfileById(@Nullable String profileId) {
        if (connectionManager == null || profileId == null || profileId.isBlank()) {
            return null;
        }
        try (Connection connection = connectionManager.openConnection()) {
            return loadProfileByIdInTransaction(connection, profileId);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    public ProfileRecord loadProfileByNpcUuid(@Nullable UUID npcUuid) {
        if (connectionManager == null || npcUuid == null) {
            return null;
        }
        try (Connection connection = connectionManager.openConnection()) {
            return loadProfileByNpcUuidInTransaction(connection, npcUuid);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    public String loadActiveSnapshotPayload(@Nullable String profileId, @Nullable String snapshotType) {
        if (connectionManager == null
                || profileId == null
                || profileId.isBlank()
                || snapshotType == null
                || snapshotType.isBlank()) {
            return null;
        }
        try (Connection connection = connectionManager.openConnection()) {
            return loadActiveSnapshotPayloadInTransaction(connection, profileId, snapshotType);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nonnull
    public LinkedHashSet<String> listActiveSnapshotTypes(@Nullable String profileId) {
        if (connectionManager == null || profileId == null || profileId.isBlank()) {
            return new LinkedHashSet<>();
        }
        try (Connection connection = connectionManager.openConnection()) {
            return loadActiveSnapshotTypesInTransaction(connection, profileId);
        } catch (Exception ignored) {
            return new LinkedHashSet<>();
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

    @Nullable
    ProfileRecord loadProfileByNpcUuidInTransaction(@Nonnull Connection connection,
                                                    @Nonnull UUID npcUuid) throws Exception {
        String profileId = resolveProfileIdInTransaction(connection, npcUuid);
        if (profileId == null || profileId.isBlank()) {
            return null;
        }
        return loadProfileByIdInTransaction(connection, profileId);
    }

    void upsertProfileInTransaction(@Nonnull Connection connection, @Nonnull ProfileUpdate update) throws Exception {
        ProfileOwnerMutation ownerMutation = update.ownerUuid() == null
                ? ProfileOwnerMutation.unchanged()
                : ProfileOwnerMutation.set(update.ownerUuid());
        upsertProfileInTransaction(connection, update, ownerMutation);
    }

    void upsertProfileInTransaction(@Nonnull Connection connection,
                                    @Nonnull ProfileUpdate update,
                                    @Nonnull ProfileOwnerMutation ownerMutation) throws Exception {
        Objects.requireNonNull(ownerMutation, "ownerMutation");
        String npcUuidString = update.npcUuid().toString();
        String profileId = resolveOrCreateProfileIdInTransaction(connection, update.npcUuid());
        long nowMs = System.currentTimeMillis();
        String currentNpcUuid = resolveEffectiveCurrentUuidForUpsert(connection, profileId, npcUuidString);
        ExistingProfileRow existingRow = loadExistingProfileRowInTransaction(connection, profileId);
        String ownerUuid = switch (ownerMutation.kind()) {
            case SET -> ownerMutation.ownerUuid().toString();
            case CLEAR -> null;
            case UNCHANGED -> existingRow == null ? null : existingRow.ownerUuid();
        };
        String displayName = trimToNull(update.displayName());
        if (displayName == null && existingRow != null) {
            displayName = existingRow.displayName();
        }
        String roleId = trimToNull(update.roleId());
        if (roleId == null && existingRow != null) {
            roleId = existingRow.roleId();
        }
        String stateJson = NpcProfileStateJsonCodec.merge(
                existingRow == null ? null : existingRow.stateJson(),
                update,
                ownerMutation,
                ownerMutation.kind() == ProfileOwnerMutation.Kind.UNCHANGED
                        && existingRow != null
                        && (existingRow.ownerUuid() == null
                            ? update.ownerUuid() == null
                            : update.ownerUuid() != null
                                && update.ownerUuid().toString().equals(existingRow.ownerUuid()))
        );
        String stateHash = stateJson != null ? Integer.toHexString(stateJson.hashCode()) : null;
        boolean profileUnchanged = existingRow != null
                && Objects.equals(existingRow.currentNpcUuid(), currentNpcUuid)
                && Objects.equals(existingRow.ownerUuid(), ownerUuid)
                && Objects.equals(existingRow.displayName(), displayName)
                && Objects.equals(existingRow.roleId(), roleId)
                && Objects.equals(existingRow.stateJson(), stateJson)
                && Objects.equals(existingRow.stateHash(), stateHash);

        if (!profileUnchanged) {
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO npc_profiles (
                        profile_id, current_npc_uuid, owner_uuid, display_name, role_id,
                        state_json, state_hash, last_world_name, created_at_ms, updated_at_ms, last_active_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(profile_id) DO UPDATE SET
                        current_npc_uuid = excluded.current_npc_uuid,
                        owner_uuid = excluded.owner_uuid,
                        display_name = excluded.display_name,
                        role_id = excluded.role_id,
                        state_json = excluded.state_json,
                        state_hash = excluded.state_hash,
                        updated_at_ms = excluded.updated_at_ms,
                        last_active_at_ms = excluded.last_active_at_ms
                    """
            )) {
                statement.setString(1, profileId);
                statement.setString(2, currentNpcUuid);
                statement.setString(3, ownerUuid);
                statement.setString(4, displayName);
                statement.setString(5, roleId);
                statement.setString(6, stateJson);
                statement.setString(7, stateHash);
                statement.setString(8, null);
                statement.setLong(9, nowMs);
                statement.setLong(10, nowMs);
                statement.setLong(11, nowMs);
                statement.executeUpdate();
            }
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

    void deleteProfileTreeInTransaction(@Nonnull Connection connection, @Nonnull UUID npcUuid) throws Exception {
        String profileId = resolveProfileIdInTransaction(connection, npcUuid);
        if (profileId == null || profileId.isBlank()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM npc_profiles WHERE profile_id = ?"
        )) {
            statement.setString(1, profileId);
            statement.executeUpdate();
        }
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

        String[] sanitized = sanitizeToolIds(toolIds);
        String[] existing = loadToolLinks(connection, profileId, linkType);
        if (Arrays.equals(existing, sanitized)) {
            return;
        }

        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM npc_tool_links WHERE profile_id = ? AND link_type = ?"
        )) {
            delete.setString(1, profileId);
            delete.setString(2, linkType);
            delete.executeUpdate();
        }
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

    @Nonnull
    String[] loadAllToolLinks(@Nonnull Connection connection, @Nonnull String profileId) throws Exception {
        LinkedHashSet<String> toolIds = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT DISTINCT tool_uuid
                FROM npc_tool_links
                WHERE profile_id = ?
                ORDER BY tool_uuid
                """
        )) {
            statement.setString(1, profileId);
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

    void pruneInactiveSnapshotHistoryInTransaction(@Nonnull Connection connection,
                                                   long cutoffMs,
                                                   int maxInactivePerProfileType) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM npc_snapshots WHERE is_active = 0 AND created_at_ms < ?"
        )) {
            statement.setLong(1, cutoffMs);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                """
                DELETE FROM npc_snapshots
                WHERE snapshot_id IN (
                    SELECT snapshot_id
                    FROM (
                        SELECT snapshot_id,
                               ROW_NUMBER() OVER (
                                   PARTITION BY profile_id, snapshot_type
                                   ORDER BY created_at_ms DESC, snapshot_version DESC, snapshot_id DESC
                               ) AS row_num
                        FROM npc_snapshots
                        WHERE is_active = 0
                    ) ranked
                    WHERE ranked.row_num > ?
                )
                """
        )) {
            statement.setInt(1, Math.max(1, maxInactivePerProfileType));
            statement.executeUpdate();
        }
    }

    @Nullable
    String loadActiveSnapshotPayloadInTransaction(@Nonnull Connection connection,
                                                  @Nonnull String profileId,
                                                  @Nonnull String snapshotType) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT payload_json
                FROM npc_snapshots
                WHERE profile_id = ? AND snapshot_type = ? AND is_active = 1
                ORDER BY snapshot_version DESC
                LIMIT 1
                """
        )) {
            statement.setString(1, profileId);
            statement.setString(2, snapshotType);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return trimToNull(rs.getString("payload_json"));
            }
        }
    }

    @Nonnull
    LinkedHashSet<String> loadActiveSnapshotTypesInTransaction(@Nonnull Connection connection,
                                                               @Nonnull String profileId) throws Exception {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT snapshot_type
                FROM npc_snapshots
                WHERE profile_id = ? AND is_active = 1
                ORDER BY snapshot_type
                """
        )) {
            statement.setString(1, profileId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String snapshotType = trimToNull(rs.getString("snapshot_type"));
                    if (snapshotType != null) {
                        types.add(snapshotType);
                    }
                }
            }
        }
        return types;
    }

    @Nullable
    ProfileRecord loadProfileByIdInTransaction(@Nonnull Connection connection,
                                               @Nonnull String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT profile_id, current_npc_uuid, owner_uuid, display_name, role_id, state_json, updated_at_ms
                FROM npc_profiles
                WHERE profile_id = ?
                LIMIT 1
                """
        )) {
            statement.setString(1, profileId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                JsonObject state = NpcProfileStateJsonCodec.parse(trimToNull(rs.getString("state_json")));
                String[] toolIds = loadAllToolLinks(connection, profileId);
                String[] activeSnapshotTypes = loadActiveSnapshotTypesInTransaction(connection, profileId)
                        .toArray(new String[0]);
                UUID ownerUuid = SqliteValueCodec.parseUuid(rs.getString("owner_uuid"));
                return new ProfileRecord(
                        profileId,
                        SqliteValueCodec.parseUuid(rs.getString("current_npc_uuid")),
                        ownerUuid,
                        NpcProfileStateJsonCodec.string(state, "owner_name"),
                        trimToNull(rs.getString("role_id")),
                        trimToNull(rs.getString("display_name")),
                        NpcProfileStateJsonCodec.string(state, "custom_name"),
                        NpcProfileStateJsonCodec.bool(state, "tamed"),
                        NpcProfileStateJsonCodec.string(state, "coop_id"),
                        NpcProfileStateJsonCodec.integer(state, "coop_slot"),
                        toolIds,
                        activeSnapshotTypes,
                        rs.getLong("updated_at_ms")
                );
            }
        }
    }

    void notifyProfileChanged(@Nullable ProfileRecord before, @Nullable ProfileRecord after) {
        PersistenceChangeObserver observer = changeObserver;
        if (observer != null) {
            observer.onProfileChanged(before, after);
        }
    }

    void notifyCaptureRecorded(@Nonnull com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot snapshot,
                               @Nullable ProfileRecord profile) {
        PersistenceChangeObserver observer = changeObserver;
        if (observer != null) {
            observer.onCaptureRecorded(snapshot, profile);
        }
    }

    void notifyDeathRecorded(@Nonnull com.alechilles.alecstamework.items.CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot,
                             @Nullable ProfileRecord profile) {
        PersistenceChangeObserver observer = changeObserver;
        if (observer != null) {
            observer.onDeathRecorded(snapshot, profile);
        }
    }

    void notifyLostRecorded(@Nonnull com.alechilles.alecstamework.items.CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot,
                            @Nullable ProfileRecord profile) {
        PersistenceChangeObserver observer = changeObserver;
        if (observer != null) {
            observer.onLostRecorded(snapshot, profile);
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
    private String[] sanitizeToolIds(@Nullable String[] toolIds) {
        if (toolIds == null || toolIds.length == 0) {
            return new String[0];
        }
        return Arrays.stream(toolIds)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
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

    @Nullable
    private ExistingProfileRow loadExistingProfileRowInTransaction(@Nonnull Connection connection,
                                                                   @Nonnull String profileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT current_npc_uuid, owner_uuid, display_name, role_id, state_json, state_hash
                FROM npc_profiles
                WHERE profile_id = ?
                LIMIT 1
                """
        )) {
            statement.setString(1, profileId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ExistingProfileRow(
                        trimToNull(rs.getString("current_npc_uuid")),
                        trimToNull(rs.getString("owner_uuid")),
                        trimToNull(rs.getString("display_name")),
                        trimToNull(rs.getString("role_id")),
                        trimToNull(rs.getString("state_json")),
                        trimToNull(rs.getString("state_hash"))
                );
            }
        }
    }

    private record ExistingProfileRow(@Nullable String currentNpcUuid,
                                      @Nullable String ownerUuid,
                                      @Nullable String displayName,
                                      @Nullable String roleId,
                                      @Nullable String stateJson,
                                      @Nullable String stateHash) {
    }
}
