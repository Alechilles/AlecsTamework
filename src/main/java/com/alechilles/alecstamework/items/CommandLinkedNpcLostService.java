package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import javax.annotation.Nullable;

/**
 * Tracks linked NPCs that exhausted relocation retries and now require recovery.
 *
 * <p>The service also stores strict replacement mappings so stale originals are despawned
 * if they reappear after a replacement was created.
 */
public final class CommandLinkedNpcLostService {
    private static final String FIELD_SEPARATOR = "\t";
    private static final String VECTOR_SEPARATOR = ",";

    private final ConcurrentHashMap<UUID, LostLinkedNpcSnapshot> snapshotsByNpc = new ConcurrentHashMap<>();
    private final Path persistencePath;
    private final Object persistenceLock = new Object();
    @Nullable
    private final HytaleLogger logger;

    public CommandLinkedNpcLostService() {
        this(null, null);
    }

    public CommandLinkedNpcLostService(@Nullable Path persistencePath) {
        this(persistencePath, null);
    }

    public CommandLinkedNpcLostService(@Nullable Path persistencePath, @Nullable HytaleLogger logger) {
        this.persistencePath = persistencePath != null ? persistencePath.toAbsolutePath().normalize() : null;
        this.logger = logger;
        loadPersistedSnapshots();
    }

    public void recordLostFromRelocationDrop(UUID npcUuid,
                                             @Nullable UUID ownerUuid,
                                             @Nullable Vector3d sourceHintPosition,
                                             @Nullable Vector3d alternateSourceHintPosition,
                                             @Nullable Vector3d destination,
                                             long queuedAtMs,
                                             long droppedAtMs,
                                             int retryAttempts) {
        if (npcUuid == null) {
            return;
        }
        LostLinkedNpcSnapshot current = snapshotsByNpc.get(npcUuid);
        long now = System.currentTimeMillis();
        long resolvedQueuedAtMs = queuedAtMs > 0L ? queuedAtMs : now;
        long resolvedDroppedAtMs = droppedAtMs > 0L ? droppedAtMs : now;
        Vector3d resolvedLastKnown = copyVector(firstNonNull(sourceHintPosition, alternateSourceHintPosition, destination));
        Vector3d resolvedHome = copyVector(alternateSourceHintPosition);
        if (current != null) {
            if (resolvedLastKnown == null) {
                resolvedLastKnown = copyVector(current.lastKnownPosition());
            }
            if (resolvedHome == null) {
                resolvedHome = copyVector(current.homePosition());
            }
            resolvedQueuedAtMs = current.lastRelocationQueuedAtMs() > 0L
                    ? current.lastRelocationQueuedAtMs()
                    : resolvedQueuedAtMs;
        }
        snapshotsByNpc.put(
                npcUuid,
                new LostLinkedNpcSnapshot(
                        npcUuid,
                        resolvedLastKnown,
                        resolvedHome,
                        resolvedQueuedAtMs,
                        resolvedDroppedAtMs,
                        Math.max(0, retryAttempts),
                        null,
                        0L
                )
        );
        persistSnapshots();
        if (logger != null) {
            logger.at(Level.INFO).log(
                    "Marked linked companion as lost after relocation retries (npc="
                            + npcUuid
                            + ", owner="
                            + ownerUuid
                            + ", retries="
                            + Math.max(0, retryAttempts)
                            + ")."
            );
        }
    }

    public void onNpcAdded(Ref<EntityStore> reference, Store<EntityStore> store) {
        if (reference == null || !reference.isValid() || store == null) {
            return;
        }
        NPCEntity npc = store.getComponent(reference, NPCEntity.getComponentType());
        UUID npcUuid = npc != null ? npc.getUuid() : null;
        if (npcUuid == null) {
            return;
        }
        LostLinkedNpcSnapshot snapshot = snapshotsByNpc.get(npcUuid);
        if (snapshot == null) {
            return;
        }
        if (snapshot.replacementNpcUuid() != null) {
            npc.setToDespawn();
            if (logger != null) {
                logger.at(Level.WARNING).log(
                        "Suppressed stale linked companion after strict recovery mapping (oldNpc="
                                + npcUuid
                                + ", replacementNpc="
                                + snapshot.replacementNpcUuid()
                                + ")."
                );
            }
            return;
        }
        if (snapshotsByNpc.remove(npcUuid, snapshot)) {
            persistSnapshots();
        }
    }

    @Nullable
    public LostLinkedNpcSnapshot getLostSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return null;
        }
        LostLinkedNpcSnapshot snapshot = snapshotsByNpc.get(npcUuid);
        if (snapshot == null || !snapshot.isAwaitingRecovery()) {
            return null;
        }
        return snapshot;
    }

    public boolean isLost(UUID npcUuid) {
        return getLostSnapshot(npcUuid) != null;
    }

    @Nullable
    public UUID getReplacementUuid(UUID originalNpcUuid) {
        if (originalNpcUuid == null) {
            return null;
        }
        LostLinkedNpcSnapshot snapshot = snapshotsByNpc.get(originalNpcUuid);
        return snapshot != null ? snapshot.replacementNpcUuid() : null;
    }

    public void clearLostSnapshot(UUID npcUuid) {
        if (npcUuid == null) {
            return;
        }
        LostLinkedNpcSnapshot snapshot = snapshotsByNpc.get(npcUuid);
        if (snapshot == null || !snapshot.isAwaitingRecovery()) {
            return;
        }
        if (snapshotsByNpc.remove(npcUuid, snapshot)) {
            persistSnapshots();
        }
    }

    public void markRecovered(UUID originalNpcUuid,
                              UUID replacementNpcUuid,
                              @Nullable Vector3d lastKnownPosition,
                              @Nullable Vector3d homePosition) {
        if (originalNpcUuid == null || replacementNpcUuid == null || originalNpcUuid.equals(replacementNpcUuid)) {
            return;
        }
        LostLinkedNpcSnapshot existing = snapshotsByNpc.get(originalNpcUuid);
        long now = System.currentTimeMillis();
        Vector3d resolvedLastKnown = copyVector(lastKnownPosition);
        Vector3d resolvedHome = copyVector(homePosition);
        long queuedAtMs = now;
        int retries = 0;
        if (existing != null) {
            if (resolvedLastKnown == null) {
                resolvedLastKnown = copyVector(existing.lastKnownPosition());
            }
            if (resolvedHome == null) {
                resolvedHome = copyVector(existing.homePosition());
            }
            queuedAtMs = existing.lastRelocationQueuedAtMs() > 0L
                    ? existing.lastRelocationQueuedAtMs()
                    : queuedAtMs;
            retries = Math.max(0, existing.relocationRetryAttempts());
        }
        snapshotsByNpc.put(
                originalNpcUuid,
                new LostLinkedNpcSnapshot(
                        originalNpcUuid,
                        resolvedLastKnown,
                        resolvedHome,
                        queuedAtMs,
                        now,
                        retries,
                        replacementNpcUuid,
                        now
                )
        );
        persistSnapshots();
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
                    LostLinkedNpcSnapshot snapshot = parseSnapshot(line);
                    if (snapshot == null || snapshot.npcUuid() == null) {
                        continue;
                    }
                    snapshotsByNpc.put(snapshot.npcUuid(), snapshot);
                }
            } catch (Exception ignored) {
                // Ignore persistence read issues; runtime updates still track new lost companions.
            }
        }
    }

    private void persistSnapshots() {
        if (persistencePath == null) {
            return;
        }
        synchronized (persistenceLock) {
            try {
                if (snapshotsByNpc.isEmpty()) {
                    Files.deleteIfExists(persistencePath);
                    return;
                }
                Path parent = persistencePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                StringBuilder builder = new StringBuilder();
                for (LostLinkedNpcSnapshot snapshot : snapshotsByNpc.values()) {
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

    private String encodeSnapshot(LostLinkedNpcSnapshot snapshot) {
        return snapshot.npcUuid()
                + FIELD_SEPARATOR + encodeVector(snapshot.lastKnownPosition())
                + FIELD_SEPARATOR + encodeVector(snapshot.homePosition())
                + FIELD_SEPARATOR + snapshot.lastRelocationQueuedAtMs()
                + FIELD_SEPARATOR + snapshot.lostAtMs()
                + FIELD_SEPARATOR + snapshot.relocationRetryAttempts()
                + FIELD_SEPARATOR + encodeNullableUuid(snapshot.replacementNpcUuid())
                + FIELD_SEPARATOR + snapshot.recoveredAtMs();
    }

    @Nullable
    private LostLinkedNpcSnapshot parseSnapshot(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split(FIELD_SEPARATOR, -1);
        if (parts.length < 6) {
            return null;
        }
        UUID npcUuid = decodeNullableUuid(parts[0]);
        if (npcUuid == null) {
            return null;
        }
        Vector3d lastKnown = decodeVector(parts[1]);
        Vector3d home = decodeVector(parts[2]);
        long queuedAtMs = parseLong(parts[3], System.currentTimeMillis());
        long lostAtMs = parseLong(parts[4], queuedAtMs);
        int retries = Math.max(0, parseInt(parts[5], 0));
        UUID replacementUuid = parts.length > 6 ? decodeNullableUuid(parts[6]) : null;
        long recoveredAtMs = parts.length > 7 ? parseLong(parts[7], 0L) : 0L;
        return new LostLinkedNpcSnapshot(
                npcUuid,
                lastKnown,
                home,
                queuedAtMs,
                lostAtMs,
                retries,
                replacementUuid,
                recoveredAtMs
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

    private String encodeVector(@Nullable Vector3d vector) {
        if (vector == null) {
            return "";
        }
        return vector.x + VECTOR_SEPARATOR + vector.y + VECTOR_SEPARATOR + vector.z;
    }

    @Nullable
    private Vector3d decodeVector(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] values = raw.split(VECTOR_SEPARATOR, -1);
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

    private int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Nullable
    private Vector3d copyVector(@Nullable Vector3d value) {
        return value != null ? new Vector3d(value) : null;
    }

    @Nullable
    private Vector3d firstNonNull(@Nullable Vector3d first,
                                  @Nullable Vector3d second,
                                  @Nullable Vector3d third) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return third;
    }

    /**
     * Snapshot of a linked companion that can no longer be reached by relocation.
     */
    public record LostLinkedNpcSnapshot(UUID npcUuid,
                                        @Nullable Vector3d lastKnownPosition,
                                        @Nullable Vector3d homePosition,
                                        long lastRelocationQueuedAtMs,
                                        long lostAtMs,
                                        int relocationRetryAttempts,
                                        @Nullable UUID replacementNpcUuid,
                                        long recoveredAtMs) {
        public boolean isAwaitingRecovery() {
            return replacementNpcUuid == null;
        }
    }
}
