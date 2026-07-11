package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Read-side access to canonical profile UUID aliases used during startup reconciliation.
 */
public final class CompanionIdentityRepository {
    private final SqliteConnectionManager connectionManager;

    public CompanionIdentityRepository(@Nonnull SqliteConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Nonnull
    public List<AliasRecord> loadAllAliases() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT npc_uuid, profile_id, is_current, mapped_at_ms
                     FROM npc_uuid_aliases
                     ORDER BY profile_id, is_current DESC, mapped_at_ms DESC, npc_uuid
                     """
             );
             ResultSet resultSet = statement.executeQuery()) {
            List<AliasRecord> aliases = new ArrayList<>();
            while (resultSet.next()) {
                aliases.add(new AliasRecord(
                        UUID.fromString(resultSet.getString("npc_uuid")),
                        resultSet.getString("profile_id"),
                        resultSet.getInt("is_current") != 0,
                        resultSet.getLong("mapped_at_ms")
                ));
            }
            return List.copyOf(aliases);
        }
    }

    public record AliasRecord(@Nonnull UUID npcUuid,
                              @Nonnull String profileId,
                              boolean current,
                              long mappedAtMs) {
    }
}
