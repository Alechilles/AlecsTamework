package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcDeathService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Vector3d;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class DeathRepository {
    private static final String SNAPSHOT_TYPE = "death";
    private static final String LINK_TYPE = "death";

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final NpcProfileRepository profileRepository;

    public DeathRepository(@Nonnull SqliteConnectionManager connectionManager,
                           @Nonnull PersistenceWriteQueue writeQueue) {
        this(connectionManager, writeQueue, new NpcProfileRepository(connectionManager, writeQueue));
    }

    public DeathRepository(@Nonnull SqliteConnectionManager connectionManager,
                           @Nonnull PersistenceWriteQueue writeQueue,
                           @Nonnull NpcProfileRepository profileRepository) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
        this.profileRepository = profileRepository;
    }

    @Nonnull
    public List<CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot> loadAll() {
        ArrayList<CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot> rows = new ArrayList<>();
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT s.profile_id, s.payload_json, p.current_npc_uuid, p.owner_uuid, p.role_id, p.display_name
                     FROM npc_snapshots s
                     INNER JOIN npc_profiles p ON p.profile_id = s.profile_id
                     WHERE s.snapshot_type = ? AND s.is_active = 1
                     """
             )) {
            statement.setString(1, SNAPSHOT_TYPE);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID npcUuid = SqliteValueCodec.parseUuid(rs.getString("current_npc_uuid"));
                    String profileId = rs.getString("profile_id");
                    if (npcUuid == null || profileId == null || profileId.isBlank()) {
                        continue;
                    }

                    JsonObject payload = parseJsonObject(rs.getString("payload_json"));
                    if (payload == null) {
                        continue;
                    }
                    String[] toolIds = profileRepository.loadToolLinks(connection, profileId, LINK_TYPE);
                    UUID ownerId = coalesceUuid(
                            SqliteValueCodec.parseUuid(rs.getString("owner_uuid")),
                            parseUuid(payload, "ownerId")
                    );
                    String roleId = coalesceNonBlank(rs.getString("role_id"), getString(payload, "roleId"));
                    String displayName = coalesceNonBlank(rs.getString("display_name"), getString(payload, "displayName"));

                    rows.add(new CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot(
                            npcUuid,
                            ownerId,
                            getString(payload, "ownerName"),
                            toolIds,
                            roleId,
                            getBoolean(payload, "tamed", false),
                            getString(payload, "customName"),
                            displayName,
                            readVector(payload, "lastKnownPosition"),
                            readVector(payload, "homePosition"),
                            getLong(payload, "diedAtMs", System.currentTimeMillis()),
                            getLong(payload, "respawnAvailableAtMs", System.currentTimeMillis()),
                            getString(payload, "breedingConfigId"),
                            getDoubleObj(payload, "breedingHappiness"),
                            getLong(payload, "breedingCooldownUntilMs", 0L),
                            parseUuid(payload, "breedingLastPartnerUuid"),
                            getString(payload, "traitsConfigId"),
                            getLong(payload, "traitsRollSeed", 0L),
                            getString(payload, "traitsValues"),
                            getString(payload, "happinessConfigId"),
                            getDoubleObj(payload, "happinessValue"),
                            getLong(payload, "happinessLastUpdateMs", 0L),
                            getString(payload, "lifeStage"),
                            getLong(payload, "lifeStageBornAtMs", 0L),
                            getLong(payload, "lifeStageAdolescentAtMs", 0L),
                            getLong(payload, "lifeStageAdultAtMs", 0L),
                            getLong(payload, "lifeStageFullyGrownAtMs", 0L),
                            getDouble(payload, "lifeStageBabyScale", 0.55),
                            getDouble(payload, "lifeStageAdolescentScale", 0.80),
                            getDouble(payload, "lifeStageAdolescentSwitchScale", 0.80),
                            getDouble(payload, "lifeStageAdultStartScale", 0.80),
                            getDouble(payload, "lifeStageAdultSwitchScale", 1.00),
                            getDouble(payload, "lifeStageAdultScale", 1.00),
                            getBoolean(payload, "lifeStageGrowthScalingEnabled", false),
                            getString(payload, "attachmentsConfigId"),
                            getString(payload, "attachmentsValues"),
                            getBoolean(payload, "breedingEnabled", false),
                            getString(payload, "levelingConfigId"),
                            (int) getLong(payload, "levelingLevel", 1L),
                            getDouble(payload, "levelingTotalXp", 0.0),
                            getString(payload, "talentsConfigId"),
                            (int) getLong(payload, "talentsSpentPoints", 0L),
                            getString(payload, "purchasedTalentIds"),
                            parseDeathCauseKind(payload, "deathCauseKind"),
                            getString(payload, "deathSourceName"),
                            getString(payload, "lifeStageGender")
                    ));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return rows;
    }

    public boolean upsertAsync(@Nonnull CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        AtomicReference<NpcProfileRepository.ProfileRecord> beforeRef = new AtomicReference<>();
        AtomicReference<NpcProfileRepository.ProfileRecord> afterRef = new AtomicReference<>();
        return writeQueue.submit(
                "death_upsert",
                connection -> {
                    beforeRef.set(profileRepository.loadProfileByNpcUuidInTransaction(connection, snapshot.npcUuid()));
                    upsertInTransaction(connection, snapshot);
                    String profileId = profileRepository.resolveProfileIdInTransaction(connection, snapshot.npcUuid());
                    afterRef.set(profileId != null ? profileRepository.loadProfileByIdInTransaction(connection, profileId) : null);
                },
                () -> {
                    profileRepository.notifyProfileChanged(beforeRef.get(), afterRef.get());
                    profileRepository.notifyDeathRecorded(snapshot, afterRef.get());
                }
        );
    }

    public boolean deleteAsync(@Nonnull UUID npcUuid) {
        AtomicReference<NpcProfileRepository.ProfileRecord> beforeRef = new AtomicReference<>();
        AtomicReference<NpcProfileRepository.ProfileRecord> afterRef = new AtomicReference<>();
        return writeQueue.submit(
                "death_delete",
                connection -> {
                    String profileId = profileRepository.resolveProfileIdInTransaction(connection, npcUuid);
                    beforeRef.set(profileId != null ? profileRepository.loadProfileByIdInTransaction(connection, profileId) : null);
                    deleteInTransaction(connection, npcUuid);
                    afterRef.set(profileId != null ? profileRepository.loadProfileByIdInTransaction(connection, profileId) : null);
                },
                () -> profileRepository.notifyProfileChanged(beforeRef.get(), afterRef.get())
        );
    }

    void upsertInTransaction(@Nonnull Connection connection,
                             @Nonnull CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) throws Exception {
        if (snapshot.npcUuid() == null) {
            return;
        }
        profileRepository.upsertProfileInTransaction(connection, new NpcProfileRepository.ProfileUpdate(
                snapshot.npcUuid(),
                snapshot.ownerId(),
                snapshot.ownerName(),
                snapshot.roleId(),
                snapshot.displayName(),
                snapshot.customName(),
                snapshot.tamed(),
                null,
                null,
                null,
                snapshot.toolIds()
        ));
        String profileId = profileRepository.resolveOrCreateProfileIdInTransaction(connection, snapshot.npcUuid());
        profileRepository.replaceToolLinksInTransaction(connection, profileId, LINK_TYPE, snapshot.toolIds());
        profileRepository.setActiveSnapshotInTransaction(
                connection,
                profileId,
                SNAPSHOT_TYPE,
                toPayloadJson(snapshot),
                Math.max(1L, snapshot.diedAtMs())
        );
        profileRepository.setProfileStateInTransaction(connection, profileId, null, true, null, null, null);
    }

    void deleteInTransaction(@Nonnull Connection connection, @Nonnull UUID npcUuid) throws Exception {
        String profileId = profileRepository.resolveProfileIdInTransaction(connection, npcUuid);
        if (profileId == null || profileId.isBlank()) {
            return;
        }
        profileRepository.deactivateSnapshotTypeInTransaction(connection, profileId, SNAPSHOT_TYPE);
        profileRepository.replaceToolLinksInTransaction(connection, profileId, LINK_TYPE, new String[0]);
        profileRepository.setProfileStateInTransaction(connection, profileId, null, false, null, null, null);
    }

    @Nonnull
    private String toPayloadJson(@Nonnull CommandLinkedNpcDeathService.DeadLinkedNpcSnapshot snapshot) {
        JsonObject payload = new JsonObject();
        if (snapshot.ownerId() != null) {
            payload.addProperty("ownerId", snapshot.ownerId().toString());
        }
        putString(payload, "ownerName", snapshot.ownerName());
        putString(payload, "roleId", snapshot.roleId());
        payload.addProperty("tamed", snapshot.tamed());
        putString(payload, "customName", snapshot.customName());
        putString(payload, "displayName", snapshot.displayName());
        putVector(payload, "lastKnownPosition", snapshot.lastKnownPosition());
        putVector(payload, "homePosition", snapshot.homePosition());
        payload.addProperty("diedAtMs", snapshot.diedAtMs());
        payload.addProperty("respawnAvailableAtMs", snapshot.respawnAvailableAtMs());
        putString(payload, "breedingConfigId", snapshot.breedingConfigId());
        if (snapshot.breedingHappiness() != null) {
            payload.addProperty("breedingHappiness", snapshot.breedingHappiness());
        }
        payload.addProperty("breedingCooldownUntilMs", snapshot.breedingCooldownUntilMs());
        if (snapshot.breedingLastPartnerUuid() != null) {
            payload.addProperty("breedingLastPartnerUuid", snapshot.breedingLastPartnerUuid().toString());
        }
        putString(payload, "traitsConfigId", snapshot.traitsConfigId());
        payload.addProperty("traitsRollSeed", snapshot.traitsRollSeed());
        putString(payload, "traitsValues", snapshot.traitsValues());
        putString(payload, "happinessConfigId", snapshot.happinessConfigId());
        if (snapshot.happinessValue() != null) {
            payload.addProperty("happinessValue", snapshot.happinessValue());
        }
        payload.addProperty("happinessLastUpdateMs", snapshot.happinessLastUpdateMs());
        putString(payload, "lifeStage", snapshot.lifeStage());
        payload.addProperty("lifeStageBornAtMs", snapshot.lifeStageBornAtMs());
        payload.addProperty("lifeStageAdolescentAtMs", snapshot.lifeStageAdolescentAtMs());
        payload.addProperty("lifeStageAdultAtMs", snapshot.lifeStageAdultAtMs());
        payload.addProperty("lifeStageFullyGrownAtMs", snapshot.lifeStageFullyGrownAtMs());
        payload.addProperty("lifeStageBabyScale", snapshot.lifeStageBabyScale());
        payload.addProperty("lifeStageAdolescentScale", snapshot.lifeStageAdolescentScale());
        payload.addProperty("lifeStageAdolescentSwitchScale", snapshot.lifeStageAdolescentSwitchScale());
        payload.addProperty("lifeStageAdultStartScale", snapshot.lifeStageAdultStartScale());
        payload.addProperty("lifeStageAdultSwitchScale", snapshot.lifeStageAdultSwitchScale());
        payload.addProperty("lifeStageAdultScale", snapshot.lifeStageAdultScale());
        payload.addProperty("lifeStageGrowthScalingEnabled", snapshot.lifeStageGrowthScalingEnabled());
        putString(payload, "attachmentsConfigId", snapshot.attachmentsConfigId());
        putString(payload, "attachmentsValues", snapshot.attachmentsValues());
        payload.addProperty("breedingEnabled", snapshot.breedingEnabled());
        putString(payload, "levelingConfigId", snapshot.levelingConfigId());
        payload.addProperty("levelingLevel", snapshot.levelingLevel());
        payload.addProperty("levelingTotalXp", snapshot.levelingTotalXp());
        putString(payload, "talentsConfigId", snapshot.talentsConfigId());
        payload.addProperty("talentsSpentPoints", snapshot.talentsSpentPoints());
        putString(payload, "purchasedTalentIds", snapshot.purchasedTalentIds());
        if (snapshot.deathCauseKind() != null) {
            payload.addProperty("deathCauseKind", snapshot.deathCauseKind().name());
        }
        putString(payload, "deathSourceName", snapshot.deathSourceName());
        putString(payload, "lifeStageGender", snapshot.lifeStageGender());
        return payload.toString();
    }

    @Nullable
    private CommandLinkedNpcDeathService.DeathCauseKind parseDeathCauseKind(@Nonnull JsonObject source,
                                                                            @Nonnull String key) {
        String raw = getString(source, key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CommandLinkedNpcDeathService.DeathCauseKind.valueOf(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    private JsonObject parseJsonObject(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private void putString(@Nonnull JsonObject object, @Nonnull String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            object.addProperty(key, value);
        }
    }

    private void putVector(@Nonnull JsonObject target, @Nonnull String key, @Nullable Vector3d value) {
        if (value == null) {
            return;
        }
        JsonObject vector = new JsonObject();
        vector.addProperty("x", value.x);
        vector.addProperty("y", value.y);
        vector.addProperty("z", value.z);
        target.add(key, vector);
    }

    @Nullable
    private Vector3d readVector(@Nonnull JsonObject source, @Nonnull String key) {
        if (!source.has(key) || !source.get(key).isJsonObject()) {
            return null;
        }
        JsonObject vector = source.getAsJsonObject(key);
        try {
            return new Vector3d(
                    vector.get("x").getAsDouble(),
                    vector.get("y").getAsDouble(),
                    vector.get("z").getAsDouble()
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private String getString(@Nonnull JsonObject source, @Nonnull String key) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return null;
        }
        try {
            String value = source.get(key).getAsString();
            return value == null || value.isBlank() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private long getLong(@Nonnull JsonObject source, @Nonnull String key, long fallback) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return source.get(key).getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private double getDouble(@Nonnull JsonObject source, @Nonnull String key, double fallback) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return source.get(key).getAsDouble();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Nullable
    private Double getDoubleObj(@Nonnull JsonObject source, @Nonnull String key) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return null;
        }
        try {
            return source.get(key).getAsDouble();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean getBoolean(@Nonnull JsonObject source, @Nonnull String key, boolean fallback) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return source.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Nullable
    private UUID parseUuid(@Nonnull JsonObject source, @Nonnull String key) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return null;
        }
        try {
            return UUID.fromString(source.get(key).getAsString());
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private UUID coalesceUuid(@Nullable UUID first, @Nullable UUID second) {
        if (first != null) {
            return first;
        }
        return second;
    }

    @Nullable
    private String coalesceNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }
}
