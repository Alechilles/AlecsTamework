package com.alechilles.alecstamework.npc.progression;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Shared bounded cache for temporarily suppressing unreachable NPC position targets.
 */
public final class PositionTargetRejectCache {
    public static final double DEFAULT_TTL_SECONDS = 30.0;
    public static final int MAX_ENTRIES = 4096;

    private static final ConcurrentHashMap<RejectedTargetKey, Long> REJECTED_TARGETS = new ConcurrentHashMap<>();

    private PositionTargetRejectCache() {
    }

    public static boolean reject(@Nullable UUID npcUuid,
                                 @Nullable String label,
                                 @Nullable Vector3d target,
                                 double suppressSeconds) {
        return reject(npcUuid, label, target, suppressSeconds, System.currentTimeMillis());
    }

    public static boolean reject(@Nullable UUID npcUuid,
                                 @Nullable String label,
                                 @Nullable Vector3d target,
                                 double suppressSeconds,
                                 long nowMs) {
        if (npcUuid == null || target == null || !isFinite(target)) {
            return false;
        }
        double ttlSeconds = Double.isFinite(suppressSeconds) && suppressSeconds > 0.0
                ? suppressSeconds
                : DEFAULT_TTL_SECONDS;
        long ttlMs = Math.max(1L, (long) Math.ceil(ttlSeconds * 1000.0));
        REJECTED_TARGETS.put(RejectedTargetKey.from(npcUuid, label, target), nowMs + ttlMs);
        cleanup(nowMs);
        return true;
    }

    public static boolean isRejected(@Nullable UUID npcUuid,
                                     @Nullable String label,
                                     @Nullable Vector3d target,
                                     long nowMs) {
        if (npcUuid == null || target == null || !isFinite(target)) {
            return false;
        }
        RejectedTargetKey key = RejectedTargetKey.from(npcUuid, label, target);
        Long expiresAtMs = REJECTED_TARGETS.get(key);
        if (expiresAtMs == null) {
            return false;
        }
        if (nowMs >= expiresAtMs) {
            REJECTED_TARGETS.remove(key, expiresAtMs);
            return false;
        }
        return true;
    }

    public static boolean hasRejectedTargetFor(@Nullable UUID npcUuid, @Nullable String label, long nowMs) {
        if (npcUuid == null || REJECTED_TARGETS.isEmpty()) {
            return false;
        }
        String normalizedLabel = normalizeLabel(label);
        boolean found = false;
        for (var entry : REJECTED_TARGETS.entrySet()) {
            RejectedTargetKey key = entry.getKey();
            Long expiresAtMs = entry.getValue();
            if (key == null || expiresAtMs == null || nowMs >= expiresAtMs) {
                if (key != null && expiresAtMs != null) {
                    REJECTED_TARGETS.remove(key, expiresAtMs);
                }
                continue;
            }
            if (key.npcUuid().equals(npcUuid) && key.label().equals(normalizedLabel)) {
                found = true;
            }
        }
        return found;
    }

    public static void clearForTests() {
        REJECTED_TARGETS.clear();
    }

    public static int countForTests() {
        return REJECTED_TARGETS.size();
    }

    private static void cleanup(long nowMs) {
        if (REJECTED_TARGETS.size() < MAX_ENTRIES) {
            return;
        }
        REJECTED_TARGETS.entrySet().removeIf(entry -> entry == null
                || entry.getKey() == null
                || entry.getValue() == null
                || nowMs >= entry.getValue());
        int excess = REJECTED_TARGETS.size() - MAX_ENTRIES;
        if (excess <= 0) {
            return;
        }
        for (RejectedTargetKey key : REJECTED_TARGETS.keySet()) {
            if (excess <= 0) {
                return;
            }
            if (REJECTED_TARGETS.remove(key) != null) {
                excess--;
            }
        }
    }

    @Nonnull
    static String normalizeLabel(@Nullable String label) {
        if (label == null || label.isBlank()) {
            return "generic";
        }
        return label.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isFinite(@Nonnull Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private record RejectedTargetKey(@Nonnull UUID npcUuid,
                                     @Nonnull String label,
                                     int blockX,
                                     int blockY,
                                     int blockZ) {
        @Nonnull
        private static RejectedTargetKey from(@Nonnull UUID npcUuid,
                                              @Nullable String label,
                                              @Nonnull Vector3d target) {
            return new RejectedTargetKey(
                    npcUuid,
                    normalizeLabel(label),
                    (int) Math.floor(target.x),
                    (int) Math.floor(target.y),
                    (int) Math.floor(target.z)
            );
        }
    }
}
