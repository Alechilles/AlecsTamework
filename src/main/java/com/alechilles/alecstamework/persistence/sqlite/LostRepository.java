package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcLostService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.math.vector.Vector3d;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class LostRepository {
    private static final String SNAPSHOT_TYPE = "lost";

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final NpcProfileRepository profileRepository;

    public LostRepository(@Nonnull SqliteConnectionManager connectionManager,
                          @Nonnull PersistenceWriteQueue writeQueue) {
        this(connectionManager, writeQueue, new NpcProfileRepository(connectionManager, writeQueue));
    }

    public LostRepository(@Nonnull SqliteConnectionManager connectionManager,
                          @Nonnull PersistenceWriteQueue writeQueue,
                          @Nonnull NpcProfileRepository profileRepository) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
        this.profileRepository = profileRepository;
    }

    @Nonnull
    public List<CommandLinkedNpcLostService.LostLinkedNpcSnapshot> loadAll() {
        ArrayList<CommandLinkedNpcLostService.LostLinkedNpcSnapshot> rows = new ArrayList<>();
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT s.profile_id, s.payload_json, p.current_npc_uuid
                     FROM npc_snapshots s
                     INNER JOIN npc_profiles p ON p.profile_id = s.profile_id
                     WHERE s.snapshot_type = ? AND s.is_active = 1
                     """
             )) {
            statement.setString(1, SNAPSHOT_TYPE);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    UUID npcUuid = SqliteValueCodec.parseUuid(rs.getString("current_npc_uuid"));
                    if (npcUuid == null) {
                        continue;
                    }
                    JsonObject payload = parseJsonObject(rs.getString("payload_json"));
                    if (payload == null) {
                        continue;
                    }
                    rows.add(new CommandLinkedNpcLostService.LostLinkedNpcSnapshot(
                            npcUuid,
                            readVector(payload, "lastKnownPosition"),
                            readVector(payload, "homePosition"),
                            getLong(payload, "lastRelocationQueuedAtMs", 0L),
                            getLong(payload, "lostAtMs", 0L),
                            getInt(payload, "relocationRetryAttempts", 0),
                            parseUuid(payload, "replacementNpcUuid"),
                            getLong(payload, "recoveredAtMs", 0L)
                    ));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return rows;
    }

    public boolean upsertAsync(@Nonnull CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot) {
        AtomicReference<NpcProfileRepository.ProfileRecord> beforeRef = new AtomicReference<>();
        AtomicReference<NpcProfileRepository.ProfileRecord> afterRef = new AtomicReference<>();
        return writeQueue.submit(
                "lost_upsert",
                connection -> {
                    beforeRef.set(profileRepository.loadProfileByNpcUuidInTransaction(connection, snapshot.npcUuid()));
                    upsertInTransaction(connection, snapshot);
                    String profileId = profileRepository.resolveProfileIdInTransaction(connection, snapshot.npcUuid());
                    afterRef.set(profileId != null ? profileRepository.loadProfileByIdInTransaction(connection, profileId) : null);
                },
                () -> {
                    profileRepository.notifyProfileChanged(beforeRef.get(), afterRef.get());
                    if (snapshot.isAwaitingRecovery()) {
                        profileRepository.notifyLostRecorded(snapshot, afterRef.get());
                    }
                }
        );
    }

    public boolean deleteAsync(@Nonnull UUID npcUuid) {
        AtomicReference<NpcProfileRepository.ProfileRecord> beforeRef = new AtomicReference<>();
        AtomicReference<NpcProfileRepository.ProfileRecord> afterRef = new AtomicReference<>();
        return writeQueue.submit(
                "lost_delete",
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
                             @Nonnull CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot) throws Exception {
        if (snapshot.npcUuid() == null) {
            return;
        }
        profileRepository.upsertProfileInTransaction(connection, new NpcProfileRepository.ProfileUpdate(
                snapshot.npcUuid(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ));
        String profileId = profileRepository.resolveOrCreateProfileIdInTransaction(connection, snapshot.npcUuid());
        profileRepository.setActiveSnapshotInTransaction(
                connection,
                profileId,
                SNAPSHOT_TYPE,
                toPayloadJson(snapshot),
                Math.max(1L, snapshot.lostAtMs())
        );
        profileRepository.setProfileStateInTransaction(connection, profileId, null, null, true, null, null);
    }

    void deleteInTransaction(@Nonnull Connection connection, @Nonnull UUID npcUuid) throws Exception {
        String profileId = profileRepository.resolveProfileIdInTransaction(connection, npcUuid);
        if (profileId == null || profileId.isBlank()) {
            return;
        }
        profileRepository.deactivateSnapshotTypeInTransaction(connection, profileId, SNAPSHOT_TYPE);
        profileRepository.setProfileStateInTransaction(connection, profileId, null, null, false, null, null);
    }

    @Nonnull
    private String toPayloadJson(@Nonnull CommandLinkedNpcLostService.LostLinkedNpcSnapshot snapshot) {
        JsonObject payload = new JsonObject();
        putVector(payload, "lastKnownPosition", snapshot.lastKnownPosition());
        putVector(payload, "homePosition", snapshot.homePosition());
        payload.addProperty("lastRelocationQueuedAtMs", snapshot.lastRelocationQueuedAtMs());
        payload.addProperty("lostAtMs", snapshot.lostAtMs());
        payload.addProperty("relocationRetryAttempts", snapshot.relocationRetryAttempts());
        if (snapshot.replacementNpcUuid() != null) {
            payload.addProperty("replacementNpcUuid", snapshot.replacementNpcUuid().toString());
        }
        payload.addProperty("recoveredAtMs", snapshot.recoveredAtMs());
        return payload.toString();
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

    private int getInt(@Nonnull JsonObject source, @Nonnull String key, int fallback) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return source.get(key).getAsInt();
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
}
