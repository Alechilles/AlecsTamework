package com.alechilles.alecstamework.items;

import com.hypixel.hytale.math.vector.Vector3d;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Tracks linked NPCs captured into spawner items so command panels can show CAPTURED instead of UNLOADED.
 */
public final class CommandLinkedNpcCaptureService {
    private static final String FIELD_SEPARATOR = "\t";
    private static final String ARRAY_SEPARATOR = ";";

    private final ConcurrentHashMap<UUID, CapturedLinkedNpcSnapshot> capturedByNpc = new ConcurrentHashMap<>();
    private final Path persistencePath;
    private final Object persistenceLock = new Object();

    public CommandLinkedNpcCaptureService() {
        this(null);
    }

    public CommandLinkedNpcCaptureService(@Nullable Path persistencePath) {
        this.persistencePath = persistencePath != null
                ? persistencePath.toAbsolutePath().normalize()
                : null;
        loadPersistedSnapshots();
    }

    @Nullable
    public CapturedLinkedNpcSnapshot getCapturedSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }
        return capturedByNpc.get(npcUuid);
    }

    @Nullable
    public CapturedLinkedNpcSnapshot getCapturedSnapshotForTool(UUID npcUuid, String toolId, @Nullable UUID ownerUuid) {
        CapturedLinkedNpcSnapshot snapshot = getCapturedSnapshot(npcUuid);
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
    public CapturedLinkedNpcSnapshot getCapturedSnapshotForOwner(UUID npcUuid, @Nullable UUID ownerUuid) {
        CapturedLinkedNpcSnapshot snapshot = getCapturedSnapshot(npcUuid);
        if (snapshot == null) {
            return null;
        }
        if (!isOwnerCompatible(snapshot, ownerUuid)) {
            return null;
        }
        return snapshot;
    }

    @Nullable
    public CapturedLinkedNpcSnapshot getCapturedSnapshotForToolOrOwner(UUID npcUuid,
                                                                        String toolId,
                                                                        @Nullable UUID ownerUuid) {
        CapturedLinkedNpcSnapshot byTool = getCapturedSnapshotForTool(npcUuid, toolId, ownerUuid);
        if (byTool != null) {
            return byTool;
        }
        return getCapturedSnapshotForOwner(npcUuid, ownerUuid);
    }

    public void recordCapturedSnapshot(@Nullable CapturedLinkedNpcSnapshot snapshot) {
        if (snapshot == null || snapshot.npcUuid() == null || snapshot.toolIds() == null || snapshot.toolIds().length == 0) {
            return;
        }
        String[] toolIds = sanitizeToolIds(snapshot.toolIds());
        if (toolIds.length == 0) {
            return;
        }
        capturedByNpc.put(
                snapshot.npcUuid(),
                new CapturedLinkedNpcSnapshot(
                        snapshot.npcUuid(),
                        snapshot.ownerId(),
                        toolIds,
                        snapshot.roleId(),
                        snapshot.displayName(),
                        snapshot.lastKnownPosition(),
                        snapshot.homePosition(),
                        snapshot.capturedAtMs() > 0L ? snapshot.capturedAtMs() : System.currentTimeMillis()
                )
        );
        persistSnapshots();
    }

    public void clearCapturedSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return;
        }
        if (capturedByNpc.remove(npcUuid) != null) {
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
                List<String> lines = Files.readAllLines(persistencePath, StandardCharsets.UTF_8);
                for (String line : lines) {
                    CapturedLinkedNpcSnapshot snapshot = parseSnapshot(line);
                    if (snapshot == null || snapshot.npcUuid() == null) {
                        continue;
                    }
                    capturedByNpc.put(snapshot.npcUuid(), snapshot);
                }
            } catch (Exception ignored) {
                // Ignore persistence read issues; runtime updates still keep data current.
            }
        }
    }

    private void persistSnapshots() {
        if (persistencePath == null) {
            return;
        }
        synchronized (persistenceLock) {
            try {
                if (capturedByNpc.isEmpty()) {
                    Files.deleteIfExists(persistencePath);
                    return;
                }
                Path parent = persistencePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                StringBuilder builder = new StringBuilder();
                for (CapturedLinkedNpcSnapshot snapshot : capturedByNpc.values()) {
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

    private String encodeSnapshot(CapturedLinkedNpcSnapshot snapshot) {
        return snapshot.npcUuid()
                + FIELD_SEPARATOR + encodeNullableUuid(snapshot.ownerId())
                + FIELD_SEPARATOR + encodeStringArray(snapshot.toolIds())
                + FIELD_SEPARATOR + encodeNullableString(snapshot.roleId())
                + FIELD_SEPARATOR + encodeNullableString(snapshot.displayName())
                + FIELD_SEPARATOR + encodeVector(snapshot.lastKnownPosition())
                + FIELD_SEPARATOR + encodeVector(snapshot.homePosition())
                + FIELD_SEPARATOR + snapshot.capturedAtMs();
    }

    @Nullable
    private CapturedLinkedNpcSnapshot parseSnapshot(String line) {
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
        if (toolIds.length == 0) {
            return null;
        }
        String roleId = decodeNullableString(parts[3]);
        String displayName = decodeNullableString(parts[4]);
        Vector3d lastKnownPosition = decodeVector(parts[5]);
        Vector3d homePosition = null;
        long capturedAtMs;
        if (parts.length >= 8) {
            homePosition = decodeVector(parts[6]);
            capturedAtMs = parseLong(parts[7], System.currentTimeMillis());
        } else {
            // Backward compatibility with pre-home-position persisted format.
            capturedAtMs = parseLong(parts[6], System.currentTimeMillis());
        }
        return new CapturedLinkedNpcSnapshot(
                npcUuid,
                ownerId,
                toolIds,
                roleId,
                displayName,
                lastKnownPosition,
                homePosition,
                capturedAtMs
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

    private String encodeStringArray(String[] values) {
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

    private String[] sanitizeToolIds(String[] toolIds) {
        if (toolIds == null || toolIds.length == 0) {
            return new String[0];
        }
        return Arrays.stream(toolIds)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toArray(String[]::new);
    }

    private boolean isOwnerCompatible(CapturedLinkedNpcSnapshot snapshot, @Nullable UUID ownerUuid) {
        if (snapshot == null) {
            return false;
        }
        return snapshot.ownerId() == null || ownerUuid == null || snapshot.ownerId().equals(ownerUuid);
    }

    private String encodeVector(@Nullable Vector3d vector) {
        if (vector == null) {
            return "";
        }
        return vector.x + "," + vector.y + "," + vector.z;
    }

    @Nullable
    private Vector3d decodeVector(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] values = raw.split(",", -1);
        if (values.length < 3) {
            return null;
        }
        try {
            return new Vector3d(
                    Double.parseDouble(values[0]),
                    Double.parseDouble(values[1]),
                    Double.parseDouble(values[2])
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    /**
     * Snapshot of a linked companion captured into an item.
     */
    public record CapturedLinkedNpcSnapshot(UUID npcUuid,
                                            @Nullable UUID ownerId,
                                            String[] toolIds,
                                            @Nullable String roleId,
                                            @Nullable String displayName,
                                            @Nullable Vector3d lastKnownPosition,
                                            @Nullable Vector3d homePosition,
                                            long capturedAtMs) {
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
