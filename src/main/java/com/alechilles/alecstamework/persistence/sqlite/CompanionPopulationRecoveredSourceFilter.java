package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidenceSet;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Removes physical evidence for sources superseded by a finalized lost-recovery projection.
 *
 * <p>The stale source may remain serialized in an unloaded Hytale chunk until that chunk is loaded
 * and the runtime suppression hook can despawn it. Its presence is therefore cleanup evidence, not
 * a second authoritative representation of the recovered profile.</p>
 */
final class CompanionPopulationRecoveredSourceFilter {
    private CompanionPopulationRecoveredSourceFilter() {
    }

    @Nonnull
    static List<CompanionPopulationEvidenceSet.ResolvedEvidence> filter(
            @Nonnull Connection connection,
            @Nonnull String profileId,
            @Nonnull List<CompanionPopulationEvidenceSet.ResolvedEvidence> representations
    ) throws Exception {
        if (representations.size() < 2) {
            return representations;
        }
        Set<UUID> supersededSources = loadSupersededSources(connection, profileId);
        if (supersededSources.isEmpty()) {
            return representations;
        }
        List<CompanionPopulationEvidenceSet.ResolvedEvidence> retained = new ArrayList<>();
        for (CompanionPopulationEvidenceSet.ResolvedEvidence representation : representations) {
            if (!representation.physical()
                    || !supersededSources.contains(representation.npcUuid())) {
                retained.add(representation);
            }
        }
        return List.copyOf(retained);
    }

    @Nonnull
    private static Set<UUID> loadSupersededSources(
            @Nonnull Connection connection,
            @Nonnull String profileId
    ) throws Exception {
        Set<UUID> sources = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT o.source_npc_uuid
                FROM npc_recovery_operations o
                INNER JOIN npc_profiles p ON p.profile_id = o.profile_id
                WHERE o.profile_id = ? AND o.state = 'FINALIZED' AND o.active = 0
                  AND o.source_npc_uuid IS NOT NULL AND p.current_npc_uuid IS NOT NULL
                  AND o.source_npc_uuid <> p.current_npc_uuid
                """)) {
            statement.setString(1, profileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID source = SqliteValueCodec.parseUuid(resultSet.getString(1));
                    if (source != null) {
                        sources.add(source);
                    }
                }
            }
        }
        return Set.copyOf(sources);
    }
}
