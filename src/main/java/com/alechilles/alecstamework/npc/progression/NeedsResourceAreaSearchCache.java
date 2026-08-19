package com.alechilles.alecstamework.npc.progression;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Short-lived area-level cache for immutable needs-resource candidates shared
 * by nearby companions.
 */
final class NeedsResourceAreaSearchCache {
    static final int POSITION_CACHE_CELL_SIZE_BLOCKS = 4;

    private final int maxEntries;
    private final ConcurrentHashMap<AreaKey, CachedAreaSearch> entries = new ConcurrentHashMap<>();

    NeedsResourceAreaSearchCache(int maxEntries) {
        this.maxEntries = Math.max(16, maxEntries);
    }

    /**
     * Returns a non-expired immutable candidate snapshot for a coordinator.
     */
    @Nullable
    NeedsResourceCandidates.Snapshot getSnapshot(@Nullable AreaKey key, long nowMs) {
        CachedAreaSearch cached = getCached(key, nowMs);
        return cached != null ? cached.toSnapshot(nowMs) : null;
    }

    @Nullable
    NeedsResourceCandidates.Snapshot get(@Nullable AreaKey key, long nowMs) {
        return getSnapshot(key, nowMs);
    }

    /**
     * Compatibility adapter for callers that still expect one mutable target
     * vector. The vector is created only after a candidate passes selection.
     */
    @Nullable
    AreaSearchSnapshot get(@Nullable AreaKey key,
                           @Nullable Vector3d currentPosition,
                           double radius,
                           int verticalScanRadius,
                           long nowMs) {
        CachedAreaSearch cached = getCached(key, nowMs);
        if (cached == null) {
            return null;
        }
        NeedsResourceCandidates.Snapshot snapshot = cached.snapshot();
        long remainingTtlMs = Math.max(1L, cached.expiresAtMs() - nowMs);
        LegacyTargetCoordinates legacyTarget = cached.legacyTarget();
        if (legacyTarget != null) {
            if (!legacyTarget.isUsable(currentPosition, radius, verticalScanRadius)
                    || snapshot.candidates().isEmpty()) {
                return null;
            }
            NeedsResourceCandidates.Candidate candidate = snapshot.candidates().get(0);
            return AreaSearchSnapshot.hit(
                    new Vector3d(legacyTarget.x(), legacyTarget.y(), legacyTarget.z()),
                    snapshot.foundSource(),
                    snapshot.sourceInConsumeRange(),
                    candidate.approachRadius(),
                    remainingTtlMs
            );
        }
        NeedsResourceCandidates.Candidate candidate = snapshot.select(
                currentPosition,
                radius,
                verticalScanRadius,
                ACCEPT_ALL_CANDIDATES
        );
        if (snapshot.hasCandidates() && candidate == null) {
            return null;
        }
        if (candidate == null) {
            return AreaSearchSnapshot.miss(
                    snapshot.foundSource(),
                    snapshot.sourceInConsumeRange(),
                    2.0,
                    remainingTtlMs
            );
        }
        return AreaSearchSnapshot.hit(
                new Vector3d(candidate.x() + 0.5, candidate.y() + 0.5, candidate.z() + 0.5),
                snapshot.foundSource(),
                snapshot.sourceInConsumeRange(),
                candidate.approachRadius(),
                remainingTtlMs
        );
    }

    /**
     * Stores immutable candidates for new coordinator callers.
     */
    void put(@Nullable AreaKey key,
             @Nonnull NeedsResourceCandidates.Snapshot snapshot,
             long nowMs) {
        putInternal(key, snapshot, nowMs, null);
    }

    /**
     * Removes candidates that a world-thread caller proved unusable. The
     * remaining immutable candidates keep the original expiry. An empty
     * result is removed so the coordinator can queue a fresh bounded search.
     */
    boolean invalidateCandidates(@Nullable AreaKey key,
                                  @Nonnull Predicate<NeedsResourceCandidates.Candidate> remove,
                                  long nowMs) {
        CachedAreaSearch cached = getCached(key, nowMs);
        if (cached == null) {
            return false;
        }
        NeedsResourceCandidates.Snapshot snapshot = cached.toSnapshot(nowMs);
        List<NeedsResourceCandidates.Candidate> remaining = snapshot.candidates().stream()
                .filter(remove.negate())
                .toList();
        if (remaining.size() == snapshot.candidates().size()) {
            return false;
        }
        entries.remove(key, cached);
        if (!remaining.isEmpty()) {
            entries.put(
                    key,
                    CachedAreaSearch.from(
                            new NeedsResourceCandidates.Snapshot(
                                    remaining,
                                    snapshot.foundSource(),
                                    snapshot.sourceInConsumeRange(),
                                    snapshot.ttlMs()
                            ),
                            nowMs
                    )
            );
        }
        return true;
    }

    private void putInternal(@Nullable AreaKey key,
                             @Nonnull NeedsResourceCandidates.Snapshot snapshot,
                             long nowMs,
                             @Nullable LegacyTargetCoordinates legacyTarget) {
        if (key == null || !shouldShareResult(snapshot.hasCandidates(), snapshot.foundSource())) {
            return;
        }
        pruneExpired(nowMs);
        if (entries.size() >= maxEntries) {
            return;
        }
        entries.put(key, CachedAreaSearch.from(snapshot, nowMs, legacyTarget));
    }

    /**
     * Compatibility adapter for the former single-target cache API.
     */
    void put(@Nullable AreaKey key, @Nonnull AreaSearchSnapshot snapshot, long nowMs) {
        if (snapshot.target() == null) {
            putInternal(
                    key,
                    new NeedsResourceCandidates.Snapshot(
                            List.of(),
                            snapshot.foundConsumableSource(),
                            snapshot.foundConsumableSourceInConsumeRange(),
                            snapshot.ttlMs()
                    ),
                    nowMs,
                    null
            );
            return;
        }
        NeedsResourceCandidates.Candidate candidate = toCandidate(snapshot.target(), snapshot.approachRadius());
        if (candidate == null) {
            return;
        }
        putInternal(
                key,
                new NeedsResourceCandidates.Snapshot(
                        List.of(candidate),
                        snapshot.foundConsumableSource(),
                        snapshot.foundConsumableSourceInConsumeRange(),
                        snapshot.ttlMs()
                ),
                nowMs,
                new LegacyTargetCoordinates(snapshot.target().x(), snapshot.target().y(), snapshot.target().z())
        );
    }

    void clearForTests() {
        entries.clear();
    }

    static boolean shouldShareResult(boolean hasTarget, boolean foundConsumableSource) {
        return hasTarget || !foundConsumableSource;
    }

    private static final Predicate<NeedsResourceCandidates.Candidate> ACCEPT_ALL_CANDIDATES = candidate -> true;

    @Nullable
    private CachedAreaSearch getCached(@Nullable AreaKey key, long nowMs) {
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
        return cached;
    }

    private void pruneExpired(long nowMs) {
        if (entries.size() < maxEntries) {
            return;
        }
        entries.entrySet().removeIf(entry -> entry == null
                || entry.getValue() == null
                || nowMs >= entry.getValue().expiresAtMs());
    }

    @Nullable
    private static NeedsResourceCandidates.Candidate toCandidate(@Nonnull Vector3d target,
                                                                  double approachRadius) {
        if (!Double.isFinite(target.x)
                || !Double.isFinite(target.y)
                || !Double.isFinite(target.z)) {
            return null;
        }
        return new NeedsResourceCandidates.Candidate(
                blockCoordinate(target.x),
                blockCoordinate(target.y),
                blockCoordinate(target.z),
                approachRadius
        );
    }

    private static int blockCoordinate(double coordinate) {
        if (coordinate <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (coordinate >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.floor(coordinate);
    }

    private record LegacyTargetCoordinates(double x, double y, double z) {
        boolean isUsable(@Nullable Vector3d currentPosition,
                         double radius,
                         int verticalScanRadius) {
            if (currentPosition == null
                    || !Double.isFinite(currentPosition.x)
                    || !Double.isFinite(currentPosition.y)
                    || !Double.isFinite(currentPosition.z)
                    || !Double.isFinite(x)
                    || !Double.isFinite(y)
                    || !Double.isFinite(z)
                    || !Double.isFinite(radius)
                    || radius <= 0.0) {
                return false;
            }
            double dx = x - currentPosition.x;
            double dz = z - currentPosition.z;
            if ((dx * dx) + (dz * dz) > (radius * radius) + 0.000001) {
                return false;
            }
            return Math.abs(Math.floor(y) - Math.floor(currentPosition.y))
                    <= Math.max(0, verticalScanRadius);
        }
    }

    record AreaKey(@Nonnull String worldName,
                   @Nonnull String resourceKind,
                   int cellX,
                   int cellY,
                   int cellZ,
                   int radiusKey,
                   int verticalScanRadius,
                   int consumeRadiusKey,
                   int itemIdsHash,
                   @Nonnull List<String> normalizedItemIds,
                   boolean legacyItemIdsHash) {
        AreaKey {
            worldName = worldName.trim().toLowerCase(Locale.ROOT);
            resourceKind = resourceKind.trim().toLowerCase(Locale.ROOT);
            normalizedItemIds = List.copyOf(normalizedItemIds);
        }

        /**
         * Keeps direct construction source-compatible for old package callers
         * while new requests use the canonical list factory.
         */
        AreaKey(String worldName,
                String resourceKind,
                int cellX,
                int cellY,
                int cellZ,
                int radiusKey,
                int verticalScanRadius,
                int consumeRadiusKey,
                int itemIdsHash) {
            this(
                    worldName,
                    resourceKind,
                    cellX,
                    cellY,
                    cellZ,
                    radiusKey,
                    verticalScanRadius,
                    consumeRadiusKey,
                    itemIdsHash,
                    List.of(),
                    true
            );
        }

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
            return fromLegacyScalars(
                    worldName,
                    resourceKind,
                    position.x,
                    position.y,
                    position.z,
                    radius,
                    verticalScanRadius,
                    consumeRadius,
                    itemIdsHash
            );
        }

        /**
         * Builds a collision-safe key from normalized food item identity.
         */
        @Nullable
        static AreaKey from(@Nullable String worldName,
                            @Nonnull String resourceKind,
                            @Nullable Vector3d position,
                            double radius,
                            int verticalScanRadius,
                            double consumeRadius,
                            @Nullable List<String> itemIds) {
            if (position == null) {
                return null;
            }
            return from(
                    worldName,
                    resourceKind,
                    position.x,
                    position.y,
                    position.z,
                    radius,
                    verticalScanRadius,
                    consumeRadius,
                    itemIds
            );
        }

        /**
         * Scalar overload used by queued requests. It avoids creating a
         * temporary vector while building a new coordinator key.
         */
        @Nullable
        static AreaKey from(@Nullable String worldName,
                            @Nonnull String resourceKind,
                            double positionX,
                            double positionY,
                            double positionZ,
                            double radius,
                            int verticalScanRadius,
                            double consumeRadius,
                            @Nullable List<String> itemIds) {
            if (!isValidBaseInput(worldName, resourceKind, positionX, positionY, positionZ, radius)) {
                return null;
            }
            List<String> normalizedItemIds = normalizeItemIds(itemIds);
            return new AreaKey(
                    worldName,
                    resourceKind,
                    quantizedCell(positionX),
                    quantizedCell(positionY),
                    quantizedCell(positionZ),
                    Math.max(1, (int) Math.ceil(radius * 10.0)),
                    Math.max(0, verticalScanRadius),
                    Math.max(0, (int) Math.ceil(consumeRadius * 10.0)),
                    normalizedItemIds.hashCode(),
                    normalizedItemIds,
                    false
            );
        }

        @Nullable
        private static AreaKey fromLegacyScalars(@Nullable String worldName,
                                                  @Nonnull String resourceKind,
                                                  double positionX,
                                                  double positionY,
                                                  double positionZ,
                                                  double radius,
                                                  int verticalScanRadius,
                                                  double consumeRadius,
                                                  int itemIdsHash) {
            if (!isValidBaseInput(worldName, resourceKind, positionX, positionY, positionZ, radius)) {
                return null;
            }
            return new AreaKey(
                    worldName,
                    resourceKind,
                    quantizedCell(positionX),
                    quantizedCell(positionY),
                    quantizedCell(positionZ),
                    Math.max(1, (int) Math.ceil(radius * 10.0)),
                    Math.max(0, verticalScanRadius),
                    Math.max(0, (int) Math.ceil(consumeRadius * 10.0)),
                    itemIdsHash,
                    List.of(),
                    true
            );
        }

        @Nonnull
        static List<String> normalizeItemIds(@Nullable List<String> itemIds) {
            TreeSet<String> normalized = new TreeSet<>();
            if (itemIds == null) {
                return List.of();
            }
            for (String itemId : itemIds) {
                if (itemId == null || itemId.isBlank()) {
                    continue;
                }
                normalized.add(itemId.trim().toLowerCase(Locale.ROOT));
            }
            return List.copyOf(normalized);
        }

        /** Returns whether scalar coordinates still map to this shared area cell. */
        boolean containsPosition(double positionX, double positionY, double positionZ) {
            return Double.isFinite(positionX)
                    && Double.isFinite(positionY)
                    && Double.isFinite(positionZ)
                    && quantizedCell(positionX) == cellX
                    && quantizedCell(positionY) == cellY
                    && quantizedCell(positionZ) == cellZ;
        }

        private static boolean isValidBaseInput(@Nullable String worldName,
                                                @Nonnull String resourceKind,
                                                double positionX,
                                                double positionY,
                                                double positionZ,
                                                double radius) {
            return worldName != null
                    && !worldName.isBlank()
                    && resourceKind != null
                    && !resourceKind.isBlank()
                    && Double.isFinite(positionX)
                    && Double.isFinite(positionY)
                    && Double.isFinite(positionZ)
                    && Double.isFinite(radius)
                    && radius > 0.0;
        }

        private static int quantizedCell(double coordinate) {
            return Math.floorDiv(blockCoordinate(coordinate), POSITION_CACHE_CELL_SIZE_BLOCKS);
        }

        private static int blockCoordinate(double coordinate) {
            if (coordinate <= Integer.MIN_VALUE) {
                return Integer.MIN_VALUE;
            }
            if (coordinate >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int) Math.floor(coordinate);
        }
    }

    /**
     * Legacy single-target result retained while callers migrate to snapshots.
     */
    record AreaSearchSnapshot(@Nullable Vector3d target,
                              boolean foundConsumableSource,
                              boolean foundConsumableSourceInConsumeRange,
                              double approachRadius,
                              long ttlMs) {
        @Nonnull
        static AreaSearchSnapshot hit(@Nonnull Vector3d target, double approachRadius, long ttlMs) {
            return hit(target, true, true, approachRadius, ttlMs);
        }

        @Nonnull
        private static AreaSearchSnapshot hit(@Nonnull Vector3d target,
                                              boolean foundConsumableSource,
                                              boolean foundConsumableSourceInConsumeRange,
                                              double approachRadius,
                                              long ttlMs) {
            return new AreaSearchSnapshot(
                    target,
                    foundConsumableSource,
                    foundConsumableSourceInConsumeRange,
                    approachRadius,
                    ttlMs
            );
        }

        @Nonnull
        static AreaSearchSnapshot sourceAbsentMiss(long ttlMs) {
            return miss(false, false, 2.0, ttlMs);
        }

        @Nonnull
        private static AreaSearchSnapshot miss(boolean foundConsumableSource,
                                               boolean foundConsumableSourceInConsumeRange,
                                               double approachRadius,
                                               long ttlMs) {
            return new AreaSearchSnapshot(
                    null,
                    foundConsumableSource,
                    foundConsumableSourceInConsumeRange,
                    approachRadius,
                    ttlMs
            );
        }

        boolean hasTarget() {
            return target != null;
        }
    }

    private record CachedAreaSearch(@Nonnull NeedsResourceCandidates.Snapshot snapshot,
                                    @Nullable LegacyTargetCoordinates legacyTarget,
                                    long expiresAtMs) {
        @Nonnull
        static CachedAreaSearch from(@Nonnull NeedsResourceCandidates.Snapshot snapshot, long nowMs) {
            return from(snapshot, nowMs, null);
        }

        @Nonnull
        static CachedAreaSearch from(@Nonnull NeedsResourceCandidates.Snapshot snapshot,
                                     long nowMs,
                                     @Nullable LegacyTargetCoordinates legacyTarget) {
            return new CachedAreaSearch(
                    snapshot,
                    legacyTarget,
                    nowMs + Math.max(1L, snapshot.ttlMs())
            );
        }

        @Nonnull
        NeedsResourceCandidates.Snapshot toSnapshot(long nowMs) {
            return new NeedsResourceCandidates.Snapshot(
                    snapshot.candidates(),
                    snapshot.foundSource(),
                    snapshot.sourceInConsumeRange(),
                    Math.max(1L, expiresAtMs - nowMs)
            );
        }
    }
}
