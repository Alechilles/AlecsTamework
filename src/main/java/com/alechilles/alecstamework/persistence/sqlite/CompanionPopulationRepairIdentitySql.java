package com.alechilles.alecstamework.persistence.sqlite;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.alechilles.alecstamework.persistence.sqlite.CompanionPopulationSqlSupport.setUuid;

/** Resolves durable profile identities used while reconciliation repair plans are built. */
final class CompanionPopulationRepairIdentitySql {
    private CompanionPopulationRepairIdentitySql() {
    }

    @Nullable
    static String resolveProfileId(@Nonnull Connection connection, @Nonnull UUID npcUuid)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT profile_id FROM npc_uuid_aliases WHERE npc_uuid = ?
                UNION
                SELECT profile_id FROM npc_profiles WHERE current_npc_uuid = ?
                """)) {
            setUuid(statement, 1, npcUuid);
            setUuid(statement, 2, npcUuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                String profileId = null;
                while (resultSet.next()) {
                    String candidate = resultSet.getString(1);
                    if (profileId != null && !profileId.equals(candidate)) {
                        throw new IllegalStateException(
                                "NPC UUID resolves to multiple profiles: " + npcUuid
                        );
                    }
                    profileId = candidate;
                }
                return profileId;
            }
        }
    }

    @Nonnull
    static String deterministicProfileId(@Nonnull UUID npcUuid) {
        return UUID.nameUUIDFromBytes(
                ("tamework-population:" + npcUuid).getBytes(StandardCharsets.UTF_8)
        ).toString();
    }
}
