package com.alechilles.alecstamework.items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Tracks linked NPCs currently housed in coops so command panels can render IN_COOP
 * and relocation/lost flows can skip those NPCs.
 */
public final class CommandLinkedNpcCoopService {
    private static final String FIELD_SEPARATOR = "\t";
    private static final String ARRAY_SEPARATOR = ";";

    private final ConcurrentHashMap<UUID, CoopLinkedNpcSnapshot> coopedByNpc = new ConcurrentHashMap<>();
    private final Path persistencePath;
    private final Object persistenceLock = new Object();

    public CommandLinkedNpcCoopService() {
        this(null);
    }

    public CommandLinkedNpcCoopService(@Nullable Path persistencePath) {
        this.persistencePath = persistencePath != null
                ? persistencePath.toAbsolutePath().normalize()
                : null;
        loadPersistedSnapshots();
    }

    @Nullable
    public CoopLinkedNpcSnapshot getCoopSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }
        return coopedByNpc.get(npcUuid);
    }

    @Nullable
    public CoopLinkedNpcSnapshot getCoopSnapshotForTool(UUID npcUuid, String toolId, @Nullable UUID ownerUuid) {
        CoopLinkedNpcSnapshot snapshot = getCoopSnapshot(npcUuid);
        if (snapshot == null) {
            return null;
        }
        if (!snapshot.containsToolId(toolId)) {
            return null;
        }
        if (!isOwnerCompatible(snapshot, ownerUuid)) {
            return null;
        }
        return snapshot;
    }

    @Nullable
    public CoopLinkedNpcSnapshot getCoopSnapshotForOwner(UUID npcUuid, @Nullable UUID ownerUuid) {
        CoopLinkedNpcSnapshot snapshot = getCoopSnapshot(npcUuid);
        if (snapshot == null) {
            return null;
        }
        if (!isOwnerCompatible(snapshot, ownerUuid)) {
            return null;
        }
        return snapshot;
    }

    @Nullable
    public CoopLinkedNpcSnapshot getCoopSnapshotForToolOrOwner(UUID npcUuid,
                                                               String toolId,
                                                               @Nullable UUID ownerUuid) {
        CoopLinkedNpcSnapshot byTool = getCoopSnapshotForTool(npcUuid, toolId, ownerUuid);
        if (byTool != null) {
            return byTool;
        }
        return getCoopSnapshotForOwner(npcUuid, ownerUuid);
    }

    public void recordCoopSnapshot(@Nullable CoopLinkedNpcSnapshot snapshot) {
        if (snapshot == null || snapshot.npcUuid() == null) {
            return;
        }
        String[] toolIds = sanitizeToolIds(snapshot.toolIds());
        coopedByNpc.put(
                snapshot.npcUuid(),
                new CoopLinkedNpcSnapshot(
                        snapshot.npcUuid(),
                        snapshot.ownerId(),
                        toolIds,
                        snapshot.roleId(),
                        snapshot.displayName(),
                        snapshot.coopId(),
                        snapshot.housedAtMs() > 0L ? snapshot.housedAtMs() : System.currentTimeMillis()
                )
        );
        persistSnapshots();
    }

    public void clearCoopSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return;
        }
        if (coopedByNpc.remove(npcUuid) != null) {
            persistSnapshots();
        }
    }

    private void loadPersistedSnapshots() {
        if (persistencePath == null) {
            return;
        }
        synchronized (persistenceLock) {
            if (!Files.exists(persistencePath)) {
                return;
            }
            try {
                for (String line : Files.readAllLines(persistencePath, StandardCharsets.UTF_8)) {
                    CoopLinkedNpcSnapshot snapshot = parseSnapshot(line);
                    if (snapshot == null || snapshot.npcUuid() == null) {
                        continue;
                    }
                    coopedByNpc.put(snapshot.npcUuid(), snapshot);
                }
            } catch (Exception ignored) {
                // Ignore persistence read issues; runtime tracking still updates.
            }
        }
    }

    private void persistSnapshots() {
        if (persistencePath == null) {
            return;
        }
        synchronized (persistenceLock) {
            try {
                if (coopedByNpc.isEmpty()) {
                    Files.deleteIfExists(persistencePath);
                    return;
                }
                Path parent = persistencePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                StringBuilder builder = new StringBuilder();
                for (CoopLinkedNpcSnapshot snapshot : coopedByNpc.values()) {
                    if (snapshot == null || snapshot.npcUuid() == null) {
                        continue;
                    }
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(encodeSnapshot(snapshot));
                }
                Files.writeString(persistencePath, builder.toString(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // Ignore persistence write issues; runtime tracking remains available.
            }
        }
    }

    private String encodeSnapshot(CoopLinkedNpcSnapshot snapshot) {
        return snapshot.npcUuid()
                + FIELD_SEPARATOR + encodeNullableUuid(snapshot.ownerId())
                + FIELD_SEPARATOR + encodeStringArray(snapshot.toolIds())
                + FIELD_SEPARATOR + encodeNullableString(snapshot.roleId())
                + FIELD_SEPARATOR + encodeNullableString(snapshot.displayName())
                + FIELD_SEPARATOR + encodeNullableString(snapshot.coopId())
                + FIELD_SEPARATOR + snapshot.housedAtMs();
    }

    @Nullable
    private CoopLinkedNpcSnapshot parseSnapshot(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split(FIELD_SEPARATOR, -1);
        if (parts.length < 7) {
            return null;
        }
        UUID npcUuid = decodeNullableUuid(parts[0]);
        if (npcUuid == null) {
            return null;
        }
        UUID ownerId = decodeNullableUuid(parts[1]);
        String[] toolIds = sanitizeToolIds(decodeStringArray(parts[2]));
        String roleId = decodeNullableString(parts[3]);
        String displayName = decodeNullableString(parts[4]);
        String coopId = decodeNullableString(parts[5]);
        long housedAtMs = parseLong(parts[6], System.currentTimeMillis());
        return new CoopLinkedNpcSnapshot(
                npcUuid,
                ownerId,
                toolIds,
                roleId,
                displayName,
                coopId,
                housedAtMs
        );
    }

    private String encodeNullableUuid(@Nullable UUID uuid) {
        return uuid == null ? "" : uuid.toString();
    }

    @Nullable
    private UUID decodeNullableUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String encodeNullableString(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    private String decodeNullableString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            String out = new String(decoded, StandardCharsets.UTF_8);
            return out.isBlank() ? null : out;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String encodeStringArray(@Nullable String[] values) {
        if (values == null || values.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(ARRAY_SEPARATOR);
            }
            builder.append(encodeNullableString(value));
        }
        return builder.toString();
    }

    private String[] decodeStringArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        String[] parts = raw.split(ARRAY_SEPARATOR);
        ArrayList<String> out = new ArrayList<>(parts.length);
        for (String value : parts) {
            String decoded = decodeNullableString(value);
            if (decoded == null || decoded.isBlank()) {
                continue;
            }
            out.add(decoded);
        }
        return out.toArray(new String[0]);
    }

    private String[] sanitizeToolIds(@Nullable String[] toolIds) {
        if (toolIds == null || toolIds.length == 0) {
            return new String[0];
        }
        return Arrays.stream(toolIds)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toArray(String[]::new);
    }

    private boolean isOwnerCompatible(CoopLinkedNpcSnapshot snapshot, @Nullable UUID ownerUuid) {
        if (snapshot == null) {
            return false;
        }
        return snapshot.ownerId() == null || ownerUuid == null || snapshot.ownerId().equals(ownerUuid);
    }

    private long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** Snapshot of a linked companion currently housed in a coop. */
    public record CoopLinkedNpcSnapshot(UUID npcUuid,
                                        @Nullable UUID ownerId,
                                        String[] toolIds,
                                        @Nullable String roleId,
                                        @Nullable String displayName,
                                        @Nullable String coopId,
                                        long housedAtMs) {
        public boolean containsToolId(String toolId) {
            if (toolId == null || toolIds == null || toolIds.length == 0) {
                return false;
            }
            for (String value : toolIds) {
                if (toolId.equals(value)) {
                    return true;
                }
            }
            return false;
        }
    }
}
