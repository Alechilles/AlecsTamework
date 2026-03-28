package com.alechilles.alecstamework.persistence.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class CoopLedgerRepository {
    private static final String LINK_TYPE = "coop";
    private static final String UNKNOWN_WORLD = "<unknown>";

    private final SqliteConnectionManager connectionManager;
    private final PersistenceWriteQueue writeQueue;
    private final NpcProfileRepository profileRepository;

    public CoopLedgerRepository(@Nonnull SqliteConnectionManager connectionManager,
                                @Nonnull PersistenceWriteQueue writeQueue) {
        this(connectionManager, writeQueue, new NpcProfileRepository(connectionManager, writeQueue));
    }

    public CoopLedgerRepository(@Nonnull SqliteConnectionManager connectionManager,
                                @Nonnull PersistenceWriteQueue writeQueue,
                                @Nonnull NpcProfileRepository profileRepository) {
        this.connectionManager = connectionManager;
        this.writeQueue = writeQueue;
        this.profileRepository = profileRepository;
    }

    @Nonnull
    public List<CoopLedgerRow> loadAll() {
        ArrayList<CoopLedgerRow> rows = new ArrayList<>();
        try (Connection connection = connectionManager.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     """
                     SELECT c.world_name, c.coop_id, c.x, c.y, c.z, c.resident_slot,
                            c.profile_id, c.housed_npc_uuid, c.last_released_npc_uuid,
                            c.captured_at_ms, c.released_at_ms,
                            p.owner_uuid, p.role_id, p.display_name
                     FROM coop_slots c
                     LEFT JOIN npc_profiles p ON p.profile_id = c.profile_id
                     """
             );
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String worldName = rs.getString("world_name");
                String coopId = rs.getString("coop_id");
                int x = rs.getInt("x");
                int y = rs.getInt("y");
                int z = rs.getInt("z");
                int residentSlot = rs.getInt("resident_slot");
                String profileId = rs.getString("profile_id");
                String[] toolIds = new String[0];
                if (profileId != null && !profileId.isBlank()) {
                    toolIds = profileRepository.loadToolLinks(connection, profileId, LINK_TYPE);
                }
                rows.add(new CoopLedgerRow(
                        buildSlotKey(worldName, x, y, z, residentSlot),
                        worldName,
                        coopId,
                        x,
                        y,
                        z,
                        residentSlot,
                        SqliteValueCodec.parseUuid(rs.getString("housed_npc_uuid")),
                        SqliteValueCodec.parseUuid(rs.getString("last_released_npc_uuid")),
                        SqliteValueCodec.parseUuid(rs.getString("owner_uuid")),
                        toolIds,
                        rs.getString("role_id"),
                        rs.getString("display_name"),
                        rs.getLong("captured_at_ms"),
                        rs.getLong("released_at_ms"),
                        null
                ));
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return rows;
    }

    public boolean upsertSlotAsync(@Nonnull CoopLedgerRow row) {
        return writeQueue.submit("coop_slot_upsert", connection -> upsertSlotInTransaction(connection, row));
    }

    public boolean releaseAndRemapAsync(@Nonnull CoopLedgerRow row,
                                        @Nullable UUID previousNpcUuid,
                                        @Nullable UUID currentNpcUuid) {
        return writeQueue.submit(
                "coop_release_remap",
                connection -> releaseAndRemapInTransaction(connection, row, previousNpcUuid, currentNpcUuid)
        );
    }

    public boolean clearSlotAsync(@Nullable String worldName,
                                  @Nullable String coopId,
                                  int x,
                                  int y,
                                  int z,
                                  int residentSlot) {
        return writeQueue.submit(
                "coop_slot_clear",
                connection -> clearSlotInTransaction(connection, worldName, coopId, x, y, z, residentSlot)
        );
    }

    public boolean clearNpcReferencesAsync(@Nonnull UUID npcUuid) {
        return writeQueue.submit("coop_clear_npc_refs", connection -> clearNpcReferencesInTransaction(connection, npcUuid));
    }

    public boolean clearAllAsync() {
        return writeQueue.submit("coop_clear_all", this::clearAllInTransaction);
    }

    void upsertSlotInTransaction(@Nonnull Connection connection, @Nonnull CoopLedgerRow row) throws Exception {
        String coopId = normalizeIdentifier(row.coopId());
        if (coopId == null || row.residentSlot() < 0) {
            return;
        }
        String worldName = normalizeWorld(row.worldName());

        ExistingSlot existing = findExistingSlot(connection, worldName, coopId, row.x(), row.y(), row.z(), row.residentSlot());
        String profileId = existing != null ? existing.profileId : null;
        UUID housedNpcUuid = row.housedNpcUuid();
        UUID releasedNpcUuid = row.lastReleasedNpcUuid();

        if (housedNpcUuid != null) {
            profileId = profileRepository.resolveOrCreateProfileIdInTransaction(connection, housedNpcUuid);
            profileRepository.upsertProfileInTransaction(connection, new NpcProfileRepository.ProfileUpdate(
                    housedNpcUuid,
                    row.ownerId(),
                    null,
                    row.roleId(),
                    row.displayName(),
                    null,
                    null,
                    coopId,
                    row.residentSlot(),
                    null,
                    null
            ));
        } else if (releasedNpcUuid != null) {
            if (existing != null && existing.housedNpcUuid != null && !existing.housedNpcUuid.equals(releasedNpcUuid)) {
                profileRepository.remapCurrentUuidInTransaction(connection, existing.housedNpcUuid, releasedNpcUuid);
            }
            if (profileId == null || profileId.isBlank()) {
                String resolved = profileRepository.resolveProfileIdInTransaction(connection, releasedNpcUuid);
                if (resolved != null && !resolved.isBlank()) {
                    profileId = resolved;
                }
            }
            if (profileId != null && !profileId.isBlank()) {
                profileRepository.upsertProfileInTransaction(connection, new NpcProfileRepository.ProfileUpdate(
                        releasedNpcUuid,
                        row.ownerId(),
                        null,
                        row.roleId(),
                        row.displayName(),
                        null,
                        null,
                        coopId,
                        row.residentSlot(),
                        null,
                        null
                ));
            }
        }

        long nowMs = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO coop_slots (
                    world_name, coop_id, x, y, z, resident_slot,
                    profile_id, housed_npc_uuid, last_released_npc_uuid,
                    captured_at_ms, released_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(world_name, coop_id, x, y, z, resident_slot) DO UPDATE SET
                    profile_id = excluded.profile_id,
                    housed_npc_uuid = excluded.housed_npc_uuid,
                    last_released_npc_uuid = excluded.last_released_npc_uuid,
                    captured_at_ms = excluded.captured_at_ms,
                    released_at_ms = excluded.released_at_ms,
                    updated_at_ms = excluded.updated_at_ms
                """
        )) {
            int i = 1;
            statement.setString(i++, worldName);
            statement.setString(i++, coopId);
            statement.setInt(i++, row.x());
            statement.setInt(i++, row.y());
            statement.setInt(i++, row.z());
            statement.setInt(i++, row.residentSlot());
            statement.setString(i++, profileId);
            SqliteValueCodec.bindUuid(statement, i++, housedNpcUuid);
            SqliteValueCodec.bindUuid(statement, i++, releasedNpcUuid);
            statement.setLong(i++, row.housedAtMs() > 0L ? row.housedAtMs() : nowMs);
            statement.setLong(i++, Math.max(0L, row.releasedAtMs()));
            statement.setLong(i, nowMs);
            statement.executeUpdate();
        }

        if (profileId != null && !profileId.isBlank()) {
            if (row.toolIds() != null) {
                profileRepository.replaceToolLinksInTransaction(connection, profileId, LINK_TYPE, row.toolIds());
            }
            refreshCoopStateForProfile(connection, profileId);
        }
    }

    void releaseAndRemapInTransaction(@Nonnull Connection connection,
                                      @Nonnull CoopLedgerRow row,
                                      @Nullable UUID previousNpcUuid,
                                      @Nullable UUID currentNpcUuid) throws Exception {
        if (previousNpcUuid != null && currentNpcUuid != null && !previousNpcUuid.equals(currentNpcUuid)) {
            profileRepository.remapCurrentUuidInTransaction(connection, previousNpcUuid, currentNpcUuid);
        }
        upsertSlotInTransaction(connection, row);
    }

    void clearSlotInTransaction(@Nonnull Connection connection,
                                @Nullable String worldName,
                                @Nullable String coopIdRaw,
                                int x,
                                int y,
                                int z,
                                int residentSlot) throws Exception {
        String coopId = normalizeIdentifier(coopIdRaw);
        if (coopId == null || residentSlot < 0) {
            return;
        }
        String normalizedWorld = normalizeWorld(worldName);
        String profileId = null;
        try (PreparedStatement query = connection.prepareStatement(
                """
                SELECT profile_id
                FROM coop_slots
                WHERE world_name = ? AND coop_id = ? AND x = ? AND y = ? AND z = ? AND resident_slot = ?
                LIMIT 1
                """
        )) {
            query.setString(1, normalizedWorld);
            query.setString(2, coopId);
            query.setInt(3, x);
            query.setInt(4, y);
            query.setInt(5, z);
            query.setInt(6, residentSlot);
            try (ResultSet rs = query.executeQuery()) {
                if (rs.next()) {
                    profileId = rs.getString("profile_id");
                }
            }
        }
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM coop_slots WHERE world_name = ? AND coop_id = ? AND x = ? AND y = ? AND z = ? AND resident_slot = ?"
        )) {
            delete.setString(1, normalizedWorld);
            delete.setString(2, coopId);
            delete.setInt(3, x);
            delete.setInt(4, y);
            delete.setInt(5, z);
            delete.setInt(6, residentSlot);
            delete.executeUpdate();
        }
        if (profileId != null && !profileId.isBlank()) {
            boolean inCoop = refreshCoopStateForProfile(connection, profileId);
            if (!inCoop) {
                profileRepository.replaceToolLinksInTransaction(connection, profileId, LINK_TYPE, new String[0]);
            }
        }
    }

    void clearNpcReferencesInTransaction(@Nonnull Connection connection, @Nonnull UUID npcUuid) throws Exception {
        String npcUuidString = npcUuid.toString();
        LinkedHashSet<String> profileIds = new LinkedHashSet<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT DISTINCT profile_id FROM coop_slots WHERE housed_npc_uuid = ? OR last_released_npc_uuid = ?"
        )) {
            query.setString(1, npcUuidString);
            query.setString(2, npcUuidString);
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) {
                    String profileId = rs.getString("profile_id");
                    if (profileId != null && !profileId.isBlank()) {
                        profileIds.add(profileId);
                    }
                }
            }
        }

        try (PreparedStatement clearHoused = connection.prepareStatement(
                "UPDATE coop_slots SET housed_npc_uuid = NULL, updated_at_ms = ? WHERE housed_npc_uuid = ?"
        )) {
            clearHoused.setLong(1, System.currentTimeMillis());
            clearHoused.setString(2, npcUuidString);
            clearHoused.executeUpdate();
        }
        try (PreparedStatement clearReleased = connection.prepareStatement(
                "UPDATE coop_slots SET last_released_npc_uuid = NULL, updated_at_ms = ? WHERE last_released_npc_uuid = ?"
        )) {
            clearReleased.setLong(1, System.currentTimeMillis());
            clearReleased.setString(2, npcUuidString);
            clearReleased.executeUpdate();
        }

        for (String profileId : profileIds) {
            boolean inCoop = refreshCoopStateForProfile(connection, profileId);
            if (!inCoop) {
                profileRepository.replaceToolLinksInTransaction(connection, profileId, LINK_TYPE, new String[0]);
            }
        }
    }

    void clearAllInTransaction(@Nonnull Connection connection) throws Exception {
        LinkedHashSet<String> profileIds = new LinkedHashSet<>();
        try (PreparedStatement query = connection.prepareStatement("SELECT DISTINCT profile_id FROM coop_slots");
             ResultSet rs = query.executeQuery()) {
            while (rs.next()) {
                String profileId = rs.getString("profile_id");
                if (profileId != null && !profileId.isBlank()) {
                    profileIds.add(profileId);
                }
            }
        }
        try (PreparedStatement deleteSlots = connection.prepareStatement("DELETE FROM coop_slots")) {
            deleteSlots.executeUpdate();
        }
        try (PreparedStatement deleteLinks = connection.prepareStatement(
                "DELETE FROM npc_tool_links WHERE link_type = ?"
        )) {
            deleteLinks.setString(1, LINK_TYPE);
            deleteLinks.executeUpdate();
        }
        for (String profileId : profileIds) {
            profileRepository.setProfileStateInTransaction(connection, profileId, null, null, null, false, null);
        }
    }

    @Nullable
    private ExistingSlot findExistingSlot(@Nonnull Connection connection,
                                          @Nonnull String worldName,
                                          @Nonnull String coopId,
                                          int x,
                                          int y,
                                          int z,
                                          int residentSlot) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT profile_id, housed_npc_uuid, last_released_npc_uuid
                FROM coop_slots
                WHERE world_name = ? AND coop_id = ? AND x = ? AND y = ? AND z = ? AND resident_slot = ?
                LIMIT 1
                """
        )) {
            statement.setString(1, worldName);
            statement.setString(2, coopId);
            statement.setInt(3, x);
            statement.setInt(4, y);
            statement.setInt(5, z);
            statement.setInt(6, residentSlot);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ExistingSlot(
                        rs.getString("profile_id"),
                        SqliteValueCodec.parseUuid(rs.getString("housed_npc_uuid")),
                        SqliteValueCodec.parseUuid(rs.getString("last_released_npc_uuid"))
                );
            }
        }
    }

    private boolean refreshCoopStateForProfile(@Nonnull Connection connection, @Nonnull String profileId) throws Exception {
        String coopKey = null;
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT world_name, x, y, z, resident_slot
                FROM coop_slots
                WHERE profile_id = ? AND housed_npc_uuid IS NOT NULL
                LIMIT 1
                """
        )) {
            statement.setString(1, profileId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    coopKey = buildSlotKey(
                            rs.getString("world_name"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getInt("resident_slot")
                    );
                }
            }
        }
        boolean inCoop = coopKey != null;
        profileRepository.setProfileStateInTransaction(connection, profileId, null, null, null, inCoop, coopKey);
        return inCoop;
    }

    @Nonnull
    private String buildSlotKey(@Nullable String worldName, int x, int y, int z, int residentSlot) {
        String normalizedWorld = normalizeWorld(worldName);
        return normalizedWorld + "|" + x + "," + y + "," + z + "|" + residentSlot;
    }

    @Nonnull
    private String normalizeWorld(@Nullable String worldName) {
        String normalized = normalizeIdentifier(worldName);
        return normalized != null ? normalized : UNKNOWN_WORLD;
    }

    @Nullable
    private String normalizeIdentifier(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private record ExistingSlot(@Nullable String profileId,
                                @Nullable UUID housedNpcUuid,
                                @Nullable UUID lastReleasedNpcUuid) {
    }
}

