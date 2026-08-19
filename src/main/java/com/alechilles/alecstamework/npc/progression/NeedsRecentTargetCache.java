package com.alechilles.alecstamework.npc.progression;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Remembers recently confirmed needs-resource destinations so NPCs can retry a known source
 * after wandering just outside the normal bounded source scan.
 */
public final class NeedsRecentTargetCache {
    static final long DEFAULT_TTL_MS = 60_000L;
    static final double DEFAULT_MAX_DISTANCE = 48.0;
    public static final int MAX_ENTRIES = 8_192;
    private static final double SAME_TARGET_DISTANCE_SQ = 0.25;

    private final ConcurrentHashMap<UUID, RecentTarget> targetsByNpcId = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final double maxDistanceSq;

    public NeedsRecentTargetCache() {
        this(DEFAULT_TTL_MS, DEFAULT_MAX_DISTANCE);
    }

    NeedsRecentTargetCache(long ttlMs, double maxDistance) {
        this.ttlMs = Math.max(1L, ttlMs);
        double boundedDistance = Double.isFinite(maxDistance) && maxDistance > 0.0
                ? maxDistance
                : DEFAULT_MAX_DISTANCE;
        this.maxDistanceSq = boundedDistance * boundedDistance;
    }

    public void remember(@Nullable UUID npcUuid, @Nullable Vector3d target, long nowMs) {
        remember(npcUuid, null, target, nowMs);
    }

    public void remember(@Nullable UUID npcUuid,
                         @Nullable String worldName,
                         @Nullable Vector3d target,
                         long nowMs) {
        if (npcUuid == null || !isFinite(target)) {
            return;
        }
        pruneBounded(nowMs);
        targetsByNpcId.put(
                npcUuid,
                new RecentTarget(normalizeWorldName(worldName), new Vector3d(target), nowMs + ttlMs)
        );
    }

    @Nullable
    public Vector3d resolve(@Nullable UUID npcUuid, @Nullable Vector3d currentPosition, long nowMs) {
        return resolve(npcUuid, null, currentPosition, nowMs);
    }

    @Nullable
    public Vector3d resolve(@Nullable UUID npcUuid,
                            @Nullable String worldName,
                            @Nullable Vector3d currentPosition,
                            long nowMs) {
        if (npcUuid == null || !isFinite(currentPosition)) {
            return null;
        }
        RecentTarget target = targetsByNpcId.get(npcUuid);
        if (target == null) {
            return null;
        }
        if (nowMs >= target.expiresAtMs()) {
            targetsByNpcId.remove(npcUuid, target);
            return null;
        }
        if (!target.worldName().equals(normalizeWorldName(worldName))) {
            return null;
        }
        double distanceSq = target.target().distanceSquared(currentPosition);
        if (!Double.isFinite(distanceSq) || distanceSq > maxDistanceSq) {
            return null;
        }
        return new Vector3d(target.target());
    }

    public void forget(@Nullable UUID npcUuid, @Nullable Vector3d target) {
        forget(npcUuid, null, target);
    }

    public void forget(@Nullable UUID npcUuid,
                       @Nullable String worldName,
                       @Nullable Vector3d target) {
        if (npcUuid == null || !isFinite(target)) {
            return;
        }
        RecentTarget cached = targetsByNpcId.get(npcUuid);
        if (cached != null
                && cached.worldName().equals(normalizeWorldName(worldName))
                && cached.target().distanceSquared(target) <= SAME_TARGET_DISTANCE_SQ) {
            targetsByNpcId.remove(npcUuid, cached);
        }
    }

    /** Removes all recent targets owned by one world. */
    public void clearWorld(@Nullable String worldName) {
        String normalizedWorld = normalizeWorldName(worldName);
        targetsByNpcId.entrySet().removeIf(
                entry -> entry.getValue().worldName().equals(normalizedWorld)
        );
    }

    /** Clears all entries for a lifecycle owner or focused test. */
    public void clear() {
        targetsByNpcId.clear();
    }

    void clearForTests() {
        clear();
    }

    int countForTests() {
        return targetsByNpcId.size();
    }

    private static boolean isFinite(@Nullable Vector3d value) {
        return value != null
                && Double.isFinite(value.x)
                && Double.isFinite(value.y)
                && Double.isFinite(value.z);
    }

    @Nonnull
    private static String normalizeWorldName(@Nullable String worldName) {
        return worldName == null || worldName.isBlank()
                ? "global"
                : worldName.trim().toLowerCase(Locale.ROOT);
    }

    private void pruneBounded(long nowMs) {
        if (targetsByNpcId.size() < MAX_ENTRIES) {
            return;
        }
        targetsByNpcId.entrySet().removeIf(entry -> nowMs >= entry.getValue().expiresAtMs());
        while (targetsByNpcId.size() >= MAX_ENTRIES) {
            var keys = targetsByNpcId.keySet().iterator();
            if (!keys.hasNext() || targetsByNpcId.remove(keys.next()) == null) {
                return;
            }
        }
    }

    private record RecentTarget(@Nonnull String worldName,
                                @Nonnull Vector3d target,
                                long expiresAtMs) {
    }
}
