package com.alechilles.alecstamework.persistence.sqlite;

import com.alechilles.alecstamework.items.CommandLinkedNpcCaptureService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.math.vector.Vector3d;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class CaptureRepository {
    private static final String SNAPSHOT_TYPE = "capture";
    private static final String LINK_TYPE = "capture";

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final NpcProfileRepository profileRepository;

    public CaptureRepository(@Nonnull SqliteConnectionManager connectionManager,
                             @Nonnull PersistenceWriteQueue writeQueue) {
        this(connectionManager, writeQueue, new NpcProfileRepository(connectionManager, writeQueue));
    }

    public CaptureRepository(@Nonnull SqliteConnectionManager connectionManager,
                             @Nonnull PersistenceWriteQueue writeQueue,
                             @Nonnull NpcProfileRepository profileRepository) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
        this.profileRepository = profileRepository;
    }

    @Nonnull
    public List<CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot> loadAll() {
        ArrayList<CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot> rows = new ArrayList<>();
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
                    rows.add(new CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot(
                            npcUuid,
                            SqliteValueCodec.parseUuid(rs.getString("owner_uuid")),
                            toolIds,
                            coalesceNonBlank(rs.getString("role_id"), getString(payload, "roleId")),
                            coalesceNonBlank(rs.getString("display_name"), getString(payload, "displayName")),
                            readVector(payload, "lastKnownPosition"),
                            readVector(payload, "homePosition"),
                            getLong(payload, "capturedAtMs", System.currentTimeMillis())
                    ));
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return rows;
    }

    public boolean upsertAsync(@Nonnull CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot snapshot) {
        return writeQueue.submit("capture_upsert", connection -> upsertInTransaction(connection, snapshot));
    }

    public boolean deleteAsync(@Nonnull UUID npcUuid) {
        return writeQueue.submit("capture_delete", connection -> deleteInTransaction(connection, npcUuid));
    }

    void upsertInTransaction(@Nonnull Connection connection,
                             @Nonnull CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot snapshot) throws Exception {
        if (snapshot.npcUuid() == null) {
            return;
        }
        profileRepository.upsertProfileInTransaction(connection, new NpcProfileRepository.ProfileUpdate(
                snapshot.npcUuid(),
                snapshot.ownerId(),
                null,
                snapshot.roleId(),
                snapshot.displayName(),
                null,
                null,
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
                snapshot.capturedAtMs()
        );
        profileRepository.setProfileStateInTransaction(connection, profileId, true, null, null, null, null);
    }

    void deleteInTransaction(@Nonnull Connection connection, @Nonnull UUID npcUuid) throws Exception {
        String profileId = profileRepository.resolveProfileIdInTransaction(connection, npcUuid);
        if (profileId == null || profileId.isBlank()) {
            return;
        }
        profileRepository.deactivateSnapshotTypeInTransaction(connection, profileId, SNAPSHOT_TYPE);
        profileRepository.replaceToolLinksInTransaction(connection, profileId, LINK_TYPE, new String[0]);
        profileRepository.setProfileStateInTransaction(connection, profileId, false, null, null, null, null);
    }

    @Nonnull
    private String toPayloadJson(@Nonnull CommandLinkedNpcCaptureService.CapturedLinkedNpcSnapshot snapshot) {
        JsonObject payload = new JsonObject();
        putVector(payload, "lastKnownPosition", snapshot.lastKnownPosition());
        putVector(payload, "homePosition", snapshot.homePosition());
        payload.addProperty("capturedAtMs", snapshot.capturedAtMs());
        if (snapshot.roleId() != null && !snapshot.roleId().isBlank()) {
            payload.addProperty("roleId", snapshot.roleId());
        }
        if (snapshot.displayName() != null && !snapshot.displayName().isBlank()) {
            payload.addProperty("displayName", snapshot.displayName());
        }
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
        if (!vector.has("x") || !vector.has("y") || !vector.has("z")) {
            return null;
        }
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

