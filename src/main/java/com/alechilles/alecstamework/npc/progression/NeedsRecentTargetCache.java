package com.alechilles.alecstamework.npc.progression;

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
        if (npcUuid == null || !isFinite(target)) {
            return;
        }
        targetsByNpcId.put(npcUuid, new RecentTarget(new Vector3d(target), nowMs + ttlMs));
    }

    @Nullable
    public Vector3d resolve(@Nullable UUID npcUuid, @Nullable Vector3d currentPosition, long nowMs) {
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
        double distanceSq = target.target().distanceSquared(currentPosition);
        if (!Double.isFinite(distanceSq) || distanceSq > maxDistanceSq) {
            return null;
        }
        return new Vector3d(target.target());
    }

    public void forget(@Nullable UUID npcUuid, @Nullable Vector3d target) {
        if (npcUuid == null || !isFinite(target)) {
            return;
        }
        RecentTarget cached = targetsByNpcId.get(npcUuid);
        if (cached != null && cached.target().distanceSquared(target) <= SAME_TARGET_DISTANCE_SQ) {
            targetsByNpcId.remove(npcUuid, cached);
        }
    }

    void clearForTests() {
        targetsByNpcId.clear();
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

    private record RecentTarget(@Nonnull Vector3d target,
                                long expiresAtMs) {
    }
}
