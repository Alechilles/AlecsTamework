package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Owns the low-level insert for a newly allocated canonical NPC profile identity. */
final class NpcProfileIdentitySql {
    private NpcProfileIdentitySql() {
    }

    static void insertNewProfile(@Nonnull Connection connection,
                                 @Nonnull String profileId,
                                 @Nonnull UUID npcUuid,
                                 long nowMs) throws Exception {
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
    }
}
