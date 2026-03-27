package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Phase-2 profile store used to keep canonical owner/link metadata alongside domain snapshots.
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

    private final PersistenceWriteQueue writeQueue;

    public NpcProfileRepository(@Nonnull PersistenceWriteQueue writeQueue) {
        this.writeQueue = writeQueue;
    }

    public boolean upsertAsync(@Nonnull ProfileUpdate update) {
        return writeQueue.submit("npc_profile_upsert", connection -> upsert(connection, update));
    }

    private void upsert(@Nonnull Connection connection, @Nonnull ProfileUpdate update) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO npc_profiles (
                    npc_uuid, owner_uuid, owner_name, role_id,
                    display_name, custom_name, tamed,
                    coop_id, coop_slot, profile_json, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(npc_uuid) DO UPDATE SET
                    owner_uuid = excluded.owner_uuid,
                    owner_name = excluded.owner_name,
                    role_id = excluded.role_id,
                    display_name = excluded.display_name,
                    custom_name = excluded.custom_name,
                    tamed = excluded.tamed,
                    coop_id = excluded.coop_id,
                    coop_slot = excluded.coop_slot,
                    profile_json = excluded.profile_json,
                    updated_at_ms = excluded.updated_at_ms
                """
        )) {
            statement.setString(1, update.npcUuid().toString());
            SqliteValueCodec.bindUuid(statement, 2, update.ownerUuid());
            statement.setString(3, update.ownerName());
            statement.setString(4, update.roleId());
            statement.setString(5, update.displayName());
            statement.setString(6, update.customName());
            if (update.tamed() == null) {
                statement.setObject(7, null);
            } else {
                statement.setInt(7, update.tamed() ? 1 : 0);
            }
            statement.setString(8, update.coopId());
            if (update.coopSlot() == null) {
                statement.setObject(9, null);
            } else {
                statement.setInt(9, update.coopSlot());
            }
            statement.setString(10, update.profileJson());
            statement.setLong(11, System.currentTimeMillis());
            statement.executeUpdate();
        }

        try (PreparedStatement deleteLinks = connection.prepareStatement(
                "DELETE FROM npc_profile_tool_links WHERE npc_uuid = ?"
        );
             PreparedStatement insertLink = connection.prepareStatement(
                     "INSERT INTO npc_profile_tool_links (npc_uuid, tool_id) VALUES (?, ?)"
             )) {
            deleteLinks.setString(1, update.npcUuid().toString());
            deleteLinks.executeUpdate();

            if (update.toolIds() != null) {
                Arrays.stream(update.toolIds())
                        .filter(toolId -> toolId != null && !toolId.isBlank())
                        .distinct()
                        .forEach(toolId -> {
                            try {
                                insertLink.setString(1, update.npcUuid().toString());
                                insertLink.setString(2, toolId);
                                insertLink.addBatch();
                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            }
                        });
                insertLink.executeBatch();
            }
        }
    }
}
