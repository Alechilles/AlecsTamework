package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
        try (Connection connection = connectionManager.openConnection()) {
            return loadAliases(connection);
        }
    }

    /** Reads aliases and canonical population-row presence from one SQLite snapshot. */
    @Nonnull
    public PopulationIdentitySnapshot loadPopulationIdentitySnapshot() throws Exception {
        try (Connection connection = connectionManager.openConnection()) {
            connection.setAutoCommit(false);
            try {
                List<AliasRecord> aliases = loadAliases(connection);
                Set<String> profileIds = loadPopulationProfileIds(connection);
                connection.commit();
                return new PopulationIdentitySnapshot(aliases, profileIds);
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    @Nonnull
    private static List<AliasRecord> loadAliases(@Nonnull Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
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

    @Nonnull
    private static Set<String> loadPopulationProfileIds(@Nonnull Connection connection)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT profile_id FROM companion_population_state ORDER BY profile_id"
        ); ResultSet resultSet = statement.executeQuery()) {
            Set<String> profileIds = new LinkedHashSet<>();
            while (resultSet.next()) {
                profileIds.add(resultSet.getString("profile_id"));
            }
            return Set.copyOf(profileIds);
        }
    }

    public record AliasRecord(@Nonnull UUID npcUuid,
                              @Nonnull String profileId,
                              boolean current,
                              long mappedAtMs) {
    }

    public record PopulationIdentitySnapshot(@Nonnull List<AliasRecord> aliases,
                                             @Nonnull Set<String> populationProfileIds) {
        public PopulationIdentitySnapshot {
            aliases = List.copyOf(aliases);
            populationProfileIds = Set.copyOf(populationProfileIds);
        }
    }
}
