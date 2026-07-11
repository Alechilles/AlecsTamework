package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.ownership.reconciliation.CompanionPopulationEvidence;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Bounded reader for profiles, UUID aliases, active snapshots, and occupied coop slots.
 */
public final class CompanionPopulationLegacyEvidenceRepository {
    private final SqliteConnectionManager connectionManager;

    public CompanionPopulationLegacyEvidenceRepository(
            @Nonnull SqliteConnectionManager connectionManager
    ) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager");
    }

    @Nonnull
    public SnapshotDescriptor snapshotDescriptor() throws Exception {
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT
                         (SELECT COUNT(*) FROM npc_profiles),
                         COALESCE((SELECT MAX(updated_at_ms) FROM npc_profiles), 0),
                         COALESCE((SELECT MAX(created_at_ms) FROM npc_snapshots WHERE is_active = 1), 0),
                         COALESCE((SELECT MAX(updated_at_ms) FROM coop_slots), 0),
                         COALESCE((SELECT MAX(updated_at_ms) FROM profile_states), 0),
                         COALESCE((SELECT MAX(mapped_at_ms) FROM npc_uuid_aliases), 0)
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Profile evidence descriptor query returned no row.");
            }
            long total = resultSet.getLong(1);
            String generation = total
                    + ":" + resultSet.getLong(2)
                    + ":" + resultSet.getLong(3)
                    + ":" + resultSet.getLong(4)
                    + ":" + resultSet.getLong(5)
                    + ":" + resultSet.getLong(6);
            return new SnapshotDescriptor(total, generation);
        }
    }

    @Nonnull
    public Batch loadBatch(long offset, int limit, @Nonnull String source) throws Exception {
        if (offset < 0L || limit <= 0) {
            throw new IllegalArgumentException("A non-negative offset and positive limit are required.");
        }
        List<CompanionPopulationEvidence> evidence = new ArrayList<>();
        int scanned = 0;
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT
                         p.profile_id,
                         COALESCE(
                             p.current_npc_uuid,
                             (SELECT a.npc_uuid FROM npc_uuid_aliases a
                              WHERE a.profile_id = p.profile_id
                              ORDER BY a.is_current DESC, a.mapped_at_ms DESC LIMIT 1),
                             (SELECT c.housed_npc_uuid FROM coop_slots c
                              WHERE c.profile_id = p.profile_id AND c.housed_npc_uuid IS NOT NULL
                              ORDER BY c.updated_at_ms DESC LIMIT 1),
                             (SELECT c.last_released_npc_uuid FROM coop_slots c
                              WHERE c.profile_id = p.profile_id AND c.last_released_npc_uuid IS NOT NULL
                              ORDER BY c.updated_at_ms DESC LIMIT 1)
                         ) AS evidence_npc_uuid,
                         p.owner_uuid,
                         p.last_world_name,
                         CASE WHEN COALESCE(ps.capture_active, 0) = 1 OR EXISTS (
                                 SELECT 1 FROM npc_snapshots s
                                 WHERE s.profile_id = p.profile_id
                                   AND s.snapshot_type = 'capture' AND s.is_active = 1
                             ) THEN 1 ELSE 0 END AS capture_active,
                         CASE WHEN COALESCE(ps.death_active, 0) = 1 OR EXISTS (
                                 SELECT 1 FROM npc_snapshots s
                                 WHERE s.profile_id = p.profile_id
                                   AND s.snapshot_type = 'death' AND s.is_active = 1
                             ) THEN 1 ELSE 0 END AS death_active,
                         CASE WHEN COALESCE(ps.lost_active, 0) = 1 OR EXISTS (
                                 SELECT 1 FROM npc_snapshots s
                                 WHERE s.profile_id = p.profile_id
                                   AND s.snapshot_type = 'lost' AND s.is_active = 1
                             ) THEN 1 ELSE 0 END AS lost_active,
                         CASE WHEN COALESCE(ps.in_coop, 0) = 1 OR EXISTS (
                                 SELECT 1 FROM coop_slots c
                                 WHERE c.profile_id = p.profile_id AND c.housed_npc_uuid IS NOT NULL
                             ) THEN 1 ELSE 0 END AS coop_active
                     FROM npc_profiles p
                     LEFT JOIN profile_states ps ON ps.profile_id = p.profile_id
                     ORDER BY p.profile_id
                     LIMIT ? OFFSET ?
                     """)) {
            statement.setInt(1, limit);
            statement.setLong(2, offset);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    scanned++;
                    UUID npcUuid = CompanionPopulationSqlSupport.parseUuid(
                            resultSet.getString("evidence_npc_uuid")
                    );
                    String profileId = resultSet.getString("profile_id");
                    if (npcUuid == null) {
                        throw new IllegalStateException(
                                "Profile has no resolvable current, alias, or coop UUID: " + profileId
                        );
                    }
                    UUID ownerUuid = CompanionPopulationSqlSupport.parseUuid(
                            resultSet.getString("owner_uuid")
                    );
                    String worldName = resultSet.getString("last_world_name");
                    evidence.add(evidence(
                            profileId, npcUuid, ownerUuid, worldName,
                            CompanionPopulationEvidence.Kind.PROFILE_RECORD, source
                    ));
                    addActiveEvidence(
                            evidence, resultSet.getInt("capture_active") != 0,
                            profileId, npcUuid, ownerUuid, worldName,
                            CompanionPopulationEvidence.Kind.CAPTURED_SNAPSHOT, source
                    );
                    addActiveEvidence(
                            evidence, resultSet.getInt("death_active") != 0,
                            profileId, npcUuid, ownerUuid, worldName,
                            CompanionPopulationEvidence.Kind.DEATH_SNAPSHOT, source
                    );
                    addActiveEvidence(
                            evidence, resultSet.getInt("lost_active") != 0,
                            profileId, npcUuid, ownerUuid, worldName,
                            CompanionPopulationEvidence.Kind.LOST_SNAPSHOT, source
                    );
                    addActiveEvidence(
                            evidence, resultSet.getInt("coop_active") != 0,
                            profileId, npcUuid, ownerUuid, worldName,
                            CompanionPopulationEvidence.Kind.COOP_SNAPSHOT, source
                    );
                }
            }
        }
        return new Batch(List.copyOf(evidence), scanned);
    }

    /**
     * Returns aliases and journal targets that may be physical after an interrupted owner clear.
     */
    @Nonnull
    public Set<UUID> loadKnownNpcUuids() throws Exception {
        Set<UUID> known = new HashSet<>();
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT current_npc_uuid AS npc_uuid FROM npc_profiles
                     UNION
                     SELECT npc_uuid FROM npc_uuid_aliases
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                UUID npcUuid = CompanionPopulationSqlSupport.parseUuid(resultSet.getString("npc_uuid"));
                if (npcUuid != null) {
                    known.add(npcUuid);
                }
            }
        }
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT target_context_json
                     FROM companion_population_operations
                     WHERE state IN ('PREPARED', 'APPLYING', 'APPLIED', 'COMPENSATING')
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                addJournalTarget(known, resultSet.getString(1));
            }
        }
        return Set.copyOf(known);
    }

    private static void addJournalTarget(@Nonnull Set<UUID> known, String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return;
        }
        try {
            com.google.gson.JsonObject context =
                    com.google.gson.JsonParser.parseString(contextJson).getAsJsonObject();
            com.google.gson.JsonElement value = context.has("npcUuid")
                    ? context.get("npcUuid")
                    : context.get("plannedNpcUuid");
            if (value != null && !value.isJsonNull() && !value.getAsString().isBlank()) {
                known.add(UUID.fromString(value.getAsString()));
            }
        } catch (RuntimeException ignored) {
            // Recovery will report malformed journal JSON; catalog discovery stays conservative.
        }
    }

    private static void addActiveEvidence(
            @Nonnull List<CompanionPopulationEvidence> target,
            boolean active,
            @Nonnull String profileId,
            @Nonnull UUID npcUuid,
            UUID ownerUuid,
            String worldName,
            @Nonnull CompanionPopulationEvidence.Kind kind,
            @Nonnull String source
    ) {
        if (active) {
            target.add(evidence(profileId, npcUuid, ownerUuid, worldName, kind, source));
        }
    }

    @Nonnull
    private static CompanionPopulationEvidence evidence(
            @Nonnull String profileId,
            @Nonnull UUID npcUuid,
            UUID ownerUuid,
            String worldName,
            @Nonnull CompanionPopulationEvidence.Kind kind,
            @Nonnull String source
    ) {
        return new CompanionPopulationEvidence(
                "profile-state/" + profileId + "/" + kind.name().toLowerCase(java.util.Locale.ROOT),
                npcUuid,
                ownerUuid,
                kind != CompanionPopulationEvidence.Kind.PROFILE_RECORD && ownerUuid != null,
                kind,
                worldName,
                null,
                null,
                null,
                source
        );
    }

    public record SnapshotDescriptor(long total, @Nonnull String generation) {
        public SnapshotDescriptor {
            if (total < 0L || generation == null || generation.isBlank()) {
                throw new IllegalArgumentException("Invalid profile evidence snapshot descriptor.");
            }
        }
    }

    public record Batch(@Nonnull List<CompanionPopulationEvidence> evidence, int scannedUnits) {
        public Batch {
            evidence = List.copyOf(evidence);
            if (scannedUnits < 0) {
                throw new IllegalArgumentException("scannedUnits must be non-negative.");
            }
        }
    }
}
