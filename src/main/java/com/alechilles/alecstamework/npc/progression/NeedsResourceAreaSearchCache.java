package com.alechilles.alecstamework.npc.progression;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Short-lived area-level cache for needs resource search results shared by nearby companions.
 */
final class NeedsResourceAreaSearchCache {
    static final int POSITION_CACHE_CELL_SIZE_BLOCKS = 4;

    private final int maxEntries;
    private final ConcurrentHashMap<AreaKey, CachedAreaSearch> entries = new ConcurrentHashMap<>();

    NeedsResourceAreaSearchCache(int maxEntries) {
        this.maxEntries = Math.max(16, maxEntries);
    }

    @Nullable
    AreaSearchSnapshot get(@Nullable AreaKey key,
                           @Nullable Vector3d currentPosition,
                           double radius,
                           int verticalScanRadius,
                           long nowMs) {
        if (key == null) {
            return null;
        }
        CachedAreaSearch cached = entries.get(key);
        if (cached == null) {
            return null;
        }
        if (nowMs >= cached.expiresAtMs()) {
            entries.remove(key, cached);
            return null;
        }
        if (!NeedsResourceSearchCachePolicy.isCachedTargetUsable(
                currentPosition,
                cached.target(),
                radius,
                verticalScanRadius
        )) {
            return null;
        }
        return cached.toSnapshot(nowMs);
    }

    void put(@Nullable AreaKey key, @Nonnull AreaSearchSnapshot snapshot, long nowMs) {
        if (key == null || !shouldShareResult(snapshot.hasTarget(), snapshot.foundConsumableSource())) {
            return;
        }
        pruneExpired(nowMs);
        if (entries.size() >= maxEntries) {
            return;
        }
        entries.put(key, CachedAreaSearch.from(snapshot, nowMs));
    }

    void clearForTests() {
        entries.clear();
    }

    static boolean shouldShareResult(boolean hasTarget, boolean foundConsumableSource) {
        return hasTarget || !foundConsumableSource;
    }

    private void pruneExpired(long nowMs) {
        if (entries.size() < maxEntries) {
            return;
        }
        entries.entrySet().removeIf(entry -> entry == null
                || entry.getValue() == null
                || nowMs >= entry.getValue().expiresAtMs());
    }

    record AreaKey(@Nonnull String worldName,
                   @Nonnull String resourceKind,
                   int cellX,
                   int cellY,
                   int cellZ,
                   int radiusKey,
                   int verticalScanRadius,
                   int consumeRadiusKey,
                   int itemIdsHash) {
        @Nullable
        static AreaKey from(@Nullable String worldName,
                            @Nonnull String resourceKind,
                            @Nullable Vector3d position,
                            double radius,
                            int verticalScanRadius,
                            double consumeRadius,
                            int itemIdsHash) {
            if (worldName == null || worldName.isBlank() || position == null
                    || !Double.isFinite(position.x)
                    || !Double.isFinite(position.y)
                    || !Double.isFinite(position.z)
                    || !Double.isFinite(radius)
                    || radius <= 0.0) {
                return null;
            }
            return new AreaKey(
                    worldName.trim().toLowerCase(Locale.ROOT),
                    resourceKind.trim().toLowerCase(Locale.ROOT),
                    Math.floorDiv((int) Math.floor(position.x), POSITION_CACHE_CELL_SIZE_BLOCKS),
                    Math.floorDiv((int) Math.floor(position.y), POSITION_CACHE_CELL_SIZE_BLOCKS),
                    Math.floorDiv((int) Math.floor(position.z), POSITION_CACHE_CELL_SIZE_BLOCKS),
                    Math.max(1, (int) Math.ceil(radius * 10.0)),
                    Math.max(0, verticalScanRadius),
                    Math.max(0, (int) Math.ceil(consumeRadius * 10.0)),
                    itemIdsHash
            );
        }
    }

    record AreaSearchSnapshot(@Nullable Vector3d target,
                              boolean foundConsumableSource,
                              boolean foundConsumableSourceInConsumeRange,
                              double approachRadius,
                              long ttlMs) {
        @Nonnull
        static AreaSearchSnapshot hit(@Nonnull Vector3d target, double approachRadius, long ttlMs) {
            return new AreaSearchSnapshot(new Vector3d(target), true, true, approachRadius, ttlMs);
        }

        @Nonnull
        static AreaSearchSnapshot sourceAbsentMiss(long ttlMs) {
            return new AreaSearchSnapshot(null, false, false, 2.0, ttlMs);
        }

        boolean hasTarget() {
            return target != null;
        }
    }

    private record CachedAreaSearch(@Nullable Vector3d target,
                                    boolean foundConsumableSource,
                                    boolean foundConsumableSourceInConsumeRange,
                                    double approachRadius,
                                    long expiresAtMs) {
        @Nonnull
        static CachedAreaSearch from(@Nonnull AreaSearchSnapshot snapshot, long nowMs) {
            return new CachedAreaSearch(
                    snapshot.target() != null ? new Vector3d(snapshot.target()) : null,
                    snapshot.foundConsumableSource(),
                    snapshot.foundConsumableSourceInConsumeRange(),
                    snapshot.approachRadius(),
                    nowMs + Math.max(1L, snapshot.ttlMs())
            );
        }

        @Nonnull
        AreaSearchSnapshot toSnapshot(long nowMs) {
            return new AreaSearchSnapshot(
                    target != null ? new Vector3d(target) : null,
                    foundConsumableSource,
                    foundConsumableSourceInConsumeRange,
                    approachRadius,
                    Math.max(1L, expiresAtMs - nowMs)
            );
        }
    }
}
