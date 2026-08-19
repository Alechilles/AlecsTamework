package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.progression.NeedsRecentTargetCache;
import com.alechilles.alecstamework.npc.progression.NeedsResourceCandidates;
import com.alechilles.alecstamework.npc.progression.NeedsResourceSearchCoordinator;
import com.alechilles.alecstamework.npc.progression.PositionTargetRejectCache;
import com.alechilles.alecstamework.npc.progression.PositionTargetReservationCache;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

/**
 * Adapts shared immutable resource snapshots to one NPC's target state.
 *
 * <p>The adapter owns only stable IDs, scalar request data, and short-lived
 * value snapshots. It never retains an ECS reference or a live component.</p>
 */
public final class NeedsResourceTargetCacheAdapter {
    static final long TARGET_CACHE_HIT_TTL_MS = 1_500L;
    static final long TARGET_CACHE_MISS_TTL_MS = 1_000L;
    static final double TARGET_RESERVATION_TTL_SECONDS = PositionTargetReservationCache.DEFAULT_TTL_SECONDS;
    static final int MAX_LOCAL_TARGETS = 8_192;
    static final int MAX_FAST_CONSUME_TARGETS = 8_192;
    private static final double EPSILON = 0.000001;
    private static final ThreadLocal<Vector3d> CANDIDATE_PROBE =
            ThreadLocal.withInitial(Vector3d::new);
    private static final ConcurrentHashMap<UUID, FastConsumeTarget> FAST_CONSUME_TARGETS =
            new ConcurrentHashMap<>();
    private static final Map<NeedsResourceTargetCacheAdapter, Boolean> LIVE_ADAPTERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final NeedsResourceSearchCoordinator coordinator;
    private final ConcurrentHashMap<UUID, CachedTarget> cachedTargetsByNpcId = new ConcurrentHashMap<>();
    private final NeedsRecentTargetCache recentTargetCache = new NeedsRecentTargetCache();
    /** Creates an adapter backed by the process-wide search coordinator. */
    public NeedsResourceTargetCacheAdapter() {
        this(NeedsResourceSearchCoordinator.getInstance());
    }
    NeedsResourceTargetCacheAdapter(@Nonnull NeedsResourceSearchCoordinator coordinator) {
        this.coordinator = coordinator;
        LIVE_ADAPTERS.put(this, Boolean.TRUE);
    }

    /**
     * Returns a preflighted local result without building a shared request.
     * A null result means the caller must continue to the shared lookup.
     */
    @Nullable
    public Result resolveLocal(@Nonnull UUID npcUuid,
                               @Nullable String worldName,
                               @Nonnull String resourceKind,
                               double originX,
                               double originY,
                               double originZ,
                               long nowMs) {
        ConcurrentHashMap<UUID, CachedTarget> targets = targetCache(resourceKind);
        CachedTarget cached = getCachedTarget(
                targets,
                npcUuid,
                worldName,
                resourceKind,
                originX,
                originY,
                originZ,
                nowMs
        );
        if (cached == null) {
            return null;
        }
        if (cached.target() == null) {
            return Result.miss(cached.reason(), true);
        }
        if (isTargetRejected(npcUuid, resourceKind, cached.target(), nowMs)
                || isReservedByOther(npcUuid, worldName, resourceKind, cached.target(), nowMs)) {
            targets.remove(npcUuid, cached);
            clearFastConsumeTarget(npcUuid, worldName, resourceKind, cached.target());
            return null;
        }
        reserveTarget(npcUuid, worldName, resourceKind, cached.target(), nowMs);
        return Result.target(
                cached.target(),
                cached.reason(),
                cached.approachRadius(),
                cached.fastConsume(),
                true,
                null,
                false
        );
    }

    /**
     * Reads a local target first, then the shared area snapshot, then admits
     * one deferred cold search. No scanner is called from this method.
     */
    @Nonnull
    public Result resolve(@Nonnull Store<EntityStore> store,
                          @Nonnull UUID npcUuid,
                          @Nonnull NeedsResourceSearchCoordinator.Request request,
                          @Nullable String worldName,
                          double originX,
                          double originY,
                          double originZ,
                          double radius,
                          int verticalRadius,
                          boolean fastModeActive,
                          long nowMs) {
        Result local = resolveLocal(
                npcUuid,
                worldName,
                request.resourceKind(),
                originX,
                originY,
                originZ,
                nowMs
        );
        if (local != null) {
            return local;
        }
        NeedsResourceSearchCoordinator.Lookup lookup = coordinator.lookupOrEnqueue(store, npcUuid, request, nowMs);
        if (lookup.status() == NeedsResourceSearchCoordinator.Lookup.Status.DEFERRED) {
            return Result.deferred();
        }
        NeedsResourceCandidates.Snapshot snapshot = lookup.snapshot();
        if (snapshot == null) {
            return Result.miss("resource_snapshot_missing", false);
        }
        if (!snapshot.hasCandidates()) {
            if (snapshot.foundConsumableSourceInConsumeRange()) {
                Vector3d current = new Vector3d(originX, originY, originZ);
                cacheTarget(npcUuid, worldName, request.resourceKind(), current, "resource_already_in_consume_range",
                        2.0, radius, verticalRadius, nowMs, fastModeActive);
                if (fastModeActive) {
                    rememberFastConsumeTarget(
                            npcUuid,
                            worldName,
                            request.resourceKind(),
                            current,
                            nowMs + TARGET_CACHE_HIT_TTL_MS
                    );
                }
                return Result.target(
                        current,
                        "resource_already_in_consume_range",
                        2.0,
                        fastModeActive,
                        lookup.status() == NeedsResourceSearchCoordinator.Lookup.Status.HIT,
                        null,
                        !fastModeActive
                );
            }
            cacheTarget(npcUuid, worldName, request.resourceKind(), null, "resource_target_not_found", 2.0,
                    radius, verticalRadius, nowMs, false);
            return Result.miss("resource_target_not_found", false);
        }
        Selection selection = selectCandidate(snapshot, npcUuid, worldName, request.resourceKind(), originX, originY,
                originZ, radius, verticalRadius, nowMs);
        if (selection.candidate() == null) {
            if (selection.onlyRejectedOrReserved() && selection.hasRejected()) {
                coordinator.invalidateCandidates(store, request,
                        candidate -> isRejectedCandidate(npcUuid, request.resourceKind(), candidate, nowMs), nowMs);
                coordinator.lookupOrEnqueue(store, npcUuid, request, nowMs);
                return Result.deferred();
            }
            if (selection.allReserved()) {
                return Result.reserved();
            }
            return Result.miss("resource_target_out_of_range", false);
        }
        NeedsResourceCandidates.Candidate candidate = selection.candidate();
        Vector3d target = center(candidate);
        if (!reserveTarget(npcUuid, worldName, request.resourceKind(), target, nowMs)) {
            return Result.reserved();
        }
        String reason = "resource_target_search_shared";
        cacheTarget(npcUuid, worldName, request.resourceKind(), target, reason, candidate.approachRadius(),
                radius, verticalRadius, nowMs, fastModeActive);
        if (fastModeActive) {
            rememberFastConsumeTarget(
                    npcUuid,
                    worldName,
                    request.resourceKind(),
                    target,
                    nowMs + TARGET_CACHE_HIT_TTL_MS
            );
        }
        return Result.target(
                target,
                reason,
                candidate.approachRadius(),
                fastModeActive,
                true,
                candidate,
                !fastModeActive
        );
    }
    /** Adopts a recently confirmed destination without starting a cold search. */
    @Nonnull
    public Result adoptTarget(@Nonnull UUID npcUuid,
                              @Nullable String worldName,
                              @Nonnull String resourceKind,
                              @Nonnull Vector3d target,
                              double approachRadius,
                              double radius,
                              int verticalRadius,
                              long nowMs) {
        if (!reserveTarget(npcUuid, worldName, resourceKind, target, nowMs)) {
            return Result.reserved();
        }
        cacheTarget(npcUuid, worldName, resourceKind, target, "resource_target_search_recent", approachRadius,
                radius, verticalRadius, nowMs, false);
        return Result.target(
                target,
                "resource_target_search_recent",
                approachRadius,
                false,
                false,
                null,
                true
        );
    }
    /** Removes one globally unusable candidate from a shared snapshot. */
    public void invalidateCandidate(@Nonnull Store<EntityStore> store,
                                    @Nonnull NeedsResourceSearchCoordinator.Request request,
                                    @Nullable Vector3d target,
                                    long nowMs) {
        if (target == null || !isFinite(target)) {
            return;
        }
        coordinator.invalidateCandidates(store, request, candidate -> sameBlock(candidate, target), nowMs);
    }
    /** Remembers a confirmed water destination for bounded retry fallback. */
    public void rememberRecentTarget(@Nullable UUID npcUuid,
                                     @Nullable String worldName,
                                     @Nonnull String resourceKind,
                                     @Nullable Vector3d target,
                                     long nowMs) {
        recentTargetCache(resourceKind).remember(npcUuid, worldName, target, nowMs);
    }
    /** Resolves a recent destination without invoking a world search. */
    @Nullable
    public Vector3d resolveRecentTarget(@Nullable UUID npcUuid,
                                        @Nullable String worldName,
                                        @Nonnull String resourceKind,
                                        @Nullable Vector3d currentPosition,
                                        long nowMs) {
        return recentTargetCache(resourceKind).resolve(npcUuid, worldName, currentPosition, nowMs);
    }
    /** Forgets a recent destination after a path or consume failure. */
    public void forgetRecentTarget(@Nullable UUID npcUuid,
                                   @Nullable String worldName,
                                   @Nonnull String resourceKind,
                                   @Nullable Vector3d target) {
        recentTargetCache(resourceKind).forget(npcUuid, worldName, target);
    }
    /** Clears one NPC's local target and reservation marker. */
    public void clearTarget(@Nullable UUID npcUuid,
                            @Nullable String worldName,
                            @Nullable String resourceKind,
                            @Nullable Vector3d target) {
        if (npcUuid != null) {
            ConcurrentHashMap<UUID, CachedTarget> targets = targetCache(resourceKind);
            CachedTarget cached = targets.get(npcUuid);
            if (cached != null && (target == null || sameBlock(cached.target(), target))) {
                targets.remove(npcUuid, cached);
            }
        }
        releaseTarget(npcUuid, worldName, resourceKind, target);
    }
    @Nonnull
    static Selection selectCandidate(@Nonnull NeedsResourceCandidates.Snapshot snapshot, @Nullable UUID npcUuid,
                                     @Nullable String worldName, @Nullable String resourceKind, double originX,
                                     double originY, double originZ, double radius, int verticalRadius, long nowMs) {
        if (snapshot == null
                || !Double.isFinite(radius) || radius <= 0.0) {
            return Selection.none();
        }
        double radiusSquared = radius * radius;
        int clampedVerticalRadius = Math.max(0, verticalRadius);
        int considered = 0;
        int rejected = 0;
        int reserved = 0;
        for (NeedsResourceCandidates.Candidate candidate : snapshot.candidates()) {
            double dx = candidate.x() + 0.5 - originX;
            double dz = candidate.z() + 0.5 - originZ;
            if ((dx * dx) + (dz * dz) > radiusSquared + EPSILON
                    || Math.abs(Math.floor(candidate.y() + 0.5) - Math.floor(originY))
                    > clampedVerticalRadius) {
                continue;
            }
            considered++;
            if (isRejectedCandidate(npcUuid, resourceKind, candidate, nowMs)) {
                rejected++;
                continue;
            }
            if (isReservedCandidate(npcUuid, worldName, resourceKind, candidate, nowMs)) {
                reserved++;
                continue;
            }
            return Selection.selected(candidate);
        }
        return new Selection(
                null,
                considered > 0 && rejected == considered,
                considered > 0 && reserved == considered,
                rejected > 0,
                reserved > 0,
                considered > 0 && rejected + reserved == considered
        );
    }
    @Nullable
    private CachedTarget getCachedTarget(@Nonnull ConcurrentHashMap<UUID, CachedTarget> targets,
                                         @Nonnull UUID npcUuid,
                                         @Nullable String worldName,
                                         @Nonnull String resourceKind,
                                         double originX,
                                         double originY,
                                         double originZ,
                                         long nowMs) {
        CachedTarget cached = targets.get(npcUuid);
        if (cached == null) {
            return null;
        }
        if (!cached.worldName().equals(normalizeWorldName(worldName))
                || !cached.resourceKind().equals(normalizeResourceKind(resourceKind))) {
            targets.remove(npcUuid, cached);
            return null;
        }
        if (nowMs >= cached.expiresAtMs()) {
            targets.remove(npcUuid, cached);
            return null;
        }
        if (cached.target() == null) {
            return cached;
        }
        if (!isUsable(
                cached.target(),
                originX,
                originY,
                originZ,
                cached.searchRadius(),
                cached.verticalRadius()
        )) {
            targets.remove(npcUuid, cached);
            return null;
        }
        return cached;
    }
    private void cacheTarget(@Nonnull UUID npcUuid,
                             @Nullable String worldName,
                             @Nonnull String resourceKind,
                             @Nullable Vector3d target,
                             @Nonnull String reason,
                             double approachRadius,
                             double searchRadius,
                             int verticalRadius,
                             long nowMs,
                             boolean fastConsume) {
        ConcurrentHashMap<UUID, CachedTarget> targets = targetCache(resourceKind);
        pruneBounded(targets, nowMs, MAX_LOCAL_TARGETS, CachedTarget::expiresAtMs);
        targets.put(npcUuid, new CachedTarget(
                normalizeWorldName(worldName),
                normalizeResourceKind(resourceKind),
                target,
                reason,
                sanitizeApproachRadius(approachRadius),
                sanitizeSearchRadius(searchRadius),
                Math.max(0, verticalRadius),
                nowMs + (target == null ? TARGET_CACHE_MISS_TTL_MS : TARGET_CACHE_HIT_TTL_MS),
                fastConsume
        ));
    }
    private static boolean isUsable(@Nonnull Vector3d target,
                                    double originX,
                                    double originY,
                                    double originZ,
                                    double radius,
                                    int verticalRadius) {
        if (!isFinite(target)
                || !Double.isFinite(originX)
                || !Double.isFinite(originY)
                || !Double.isFinite(originZ)
                || !Double.isFinite(radius)
                || radius <= 0.0) {
            return false;
        }
        double dx = target.x - originX;
        double dz = target.z - originZ;
        return (dx * dx) + (dz * dz) <= (radius * radius) + EPSILON
                && Math.abs(Math.floor(target.y) - Math.floor(originY)) <= Math.max(0, verticalRadius);
    }
    @Nonnull
    private static Vector3d center(@Nonnull NeedsResourceCandidates.Candidate candidate) {
        return new Vector3d(candidate.x() + 0.5, candidate.y() + 0.5, candidate.z() + 0.5);
    }
    private static boolean sameBlock(@Nullable NeedsResourceCandidates.Candidate candidate,
                                     @Nullable Vector3d target) {
        return candidate != null
                && target != null
                && candidate.x() == (int) Math.floor(target.x)
                && candidate.y() == (int) Math.floor(target.y)
                && candidate.z() == (int) Math.floor(target.z);
    }
    private static boolean sameBlock(@Nullable Vector3d first, @Nullable Vector3d second) {
        return first != null && second != null
                && (int) Math.floor(first.x) == (int) Math.floor(second.x)
                && (int) Math.floor(first.y) == (int) Math.floor(second.y)
                && (int) Math.floor(first.z) == (int) Math.floor(second.z);
    }
    private static boolean isRejectedCandidate(@Nullable UUID npcUuid,
                                               @Nullable String resourceKind,
                                               @Nonnull NeedsResourceCandidates.Candidate candidate,
                                               long nowMs) {
        Vector3d probe = CANDIDATE_PROBE.get().set(candidate.x() + 0.5, candidate.y() + 0.5, candidate.z() + 0.5);
        return isTargetRejected(npcUuid, resourceKind, probe, nowMs);
    }
    private static boolean isReservedCandidate(@Nullable UUID npcUuid,
                                               @Nullable String worldName,
                                               @Nullable String resourceKind,
                                               @Nonnull NeedsResourceCandidates.Candidate candidate,
                                               long nowMs) {
        Vector3d probe = CANDIDATE_PROBE.get().set(candidate.x() + 0.5, candidate.y() + 0.5, candidate.z() + 0.5);
        return isReservedByOther(npcUuid, worldName, resourceKind, probe, nowMs);
    }
    private static boolean isFinite(@Nullable Vector3d vector) {
        return vector != null
                && Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }
    private static double sanitizeApproachRadius(double value) {
        return Double.isFinite(value) && value > EPSILON ? value : 2.0;
    }
    private static double sanitizeSearchRadius(double value) {
        return Double.isFinite(value) && value > EPSILON ? value : 12.0;
    }
    @Nonnull
    private ConcurrentHashMap<UUID, CachedTarget> targetCache(@Nullable String resourceKind) {
        return cachedTargetsByNpcId;
    }
    @Nonnull
    private NeedsRecentTargetCache recentTargetCache(@Nullable String resourceKind) {
        return recentTargetCache;
    }
    private static <T> void pruneBounded(@Nonnull ConcurrentHashMap<UUID, T> values,
                                         long nowMs,
                                         int maxEntries,
                                         @Nonnull ToLongFunction<T> expiresAt) {
        if (values.size() < maxEntries) {
            return;
        }
        values.entrySet().removeIf(entry -> nowMs >= expiresAt.applyAsLong(entry.getValue()));
        while (values.size() >= maxEntries) {
            var keys = values.keySet().iterator();
            if (!keys.hasNext() || values.remove(keys.next()) == null) {
                return;
            }
        }
    }
    static long targetCacheTtlMs(boolean hasTarget) { return hasTarget ? TARGET_CACHE_HIT_TTL_MS : TARGET_CACHE_MISS_TTL_MS; }
    static boolean cachedTargetUsableForTests(@Nonnull Vector3d target, @Nonnull Vector3d origin,
                                             double radius, int verticalRadius) {
        return isUsable(target, origin.x, origin.y, origin.z, radius, verticalRadius);
    }
    public static boolean rejectTarget(@Nullable UUID npcUuid, @Nullable String resourceKind, @Nullable Vector3d target,
                                       double suppressSeconds) {
        if (isAutoResourceType(resourceKind)) {
            long nowMs = System.currentTimeMillis();
            boolean waterRejected = rejectTarget(npcUuid, "water", target, suppressSeconds, nowMs);
            boolean foodRejected = rejectTarget(npcUuid, "food_container", target, suppressSeconds, nowMs);
            return waterRejected || foodRejected;
        }
        return rejectTarget(npcUuid, normalizeResourceKind(resourceKind), target, suppressSeconds, System.currentTimeMillis());
    }
    static boolean rejectTarget(@Nullable UUID npcUuid, @Nonnull String resourceKind, @Nullable Vector3d target,
                                double suppressSeconds, long nowMs) {
        return PositionTargetRejectCache.reject(npcUuid, normalizeResourceKind(resourceKind), target, suppressSeconds, nowMs);
    }
    static boolean isTargetRejected(@Nullable UUID npcUuid, @Nullable String resourceKind, @Nullable Vector3d target, long nowMs) {
        return PositionTargetRejectCache.isRejected(npcUuid, normalizeResourceKind(resourceKind), target, nowMs);
    }
    static boolean reserveTarget(@Nullable UUID npcUuid, @Nullable String worldName, @Nullable String resourceKind,
                                 @Nullable Vector3d target, long nowMs) {
        return PositionTargetReservationCache.reserve(npcUuid, worldName, normalizeResourceKind(resourceKind), target,
                TARGET_RESERVATION_TTL_SECONDS, nowMs);
    }
    static boolean isReservedByOther(@Nullable UUID npcUuid, @Nullable String worldName, @Nullable String resourceKind,
                                     @Nullable Vector3d target, long nowMs) {
        return PositionTargetReservationCache.isReservedByOther(npcUuid, worldName, normalizeResourceKind(resourceKind), target, nowMs);
    }
    public static void releaseTarget(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store,
                                     @Nullable String resourceKind, @Nullable Vector3d target) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        UUID npcUuid = npc == null ? null : npc.getUuid();
        releaseTarget(npcUuid, resolveWorldName(store), resourceKind, target);
    }
    static void releaseTarget(@Nullable UUID npcUuid, @Nullable String worldName, @Nullable String resourceKind,
                              @Nullable Vector3d target) {
        if (isAutoResourceType(resourceKind)) {
            releaseTarget(npcUuid, worldName, "water", target);
            releaseTarget(npcUuid, worldName, "food_container", target);
            return;
        }
        PositionTargetReservationCache.release(npcUuid, worldName, normalizeResourceKind(resourceKind), target);
        clearFastConsumeTarget(npcUuid, worldName, normalizeResourceKind(resourceKind), target);
    }
    public static boolean hasFastConsumeTarget(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store, long nowMs) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || npc.getUuid() == null) {
            return false;
        }
        FastConsumeTarget marker = FAST_CONSUME_TARGETS.get(npc.getUuid());
        if (marker == null || nowMs >= marker.expiresAtMs()) {
            if (marker != null) {
                FAST_CONSUME_TARGETS.remove(npc.getUuid(), marker);
            }
            return false;
        }
        return marker.matchesWorld(resolveWorldName(store));
    }
    static void rememberFastConsumeTargetForTests(@Nullable UUID npcUuid, @Nullable String worldName,
                                                  @Nullable String resourceKind, @Nullable Vector3d target, long expiresAtMs) {
        rememberFastConsumeTarget(npcUuid, worldName, resourceKind, target, expiresAtMs);
    }
    static boolean isFastConsumeTargetForTests(@Nullable UUID npcUuid, @Nullable String worldName,
                                               @Nullable String resourceKind, @Nullable Vector3d target, long nowMs) {
        if (npcUuid == null || target == null) {
            return false;
        }
        FastConsumeTarget marker = FAST_CONSUME_TARGETS.get(npcUuid);
        if (marker == null || nowMs >= marker.expiresAtMs()) {
            return false;
        }
        return marker.matches(worldName, normalizeResourceKind(resourceKind), target);
    }
    static void clearFastConsumeTargetsForTests() {
        FAST_CONSUME_TARGETS.clear();
    }
    static void clearAllTargetsForTests() {
        synchronized (LIVE_ADAPTERS) {
            for (NeedsResourceTargetCacheAdapter adapter : LIVE_ADAPTERS.keySet()) {
                adapter.cachedTargetsByNpcId.clear();
                adapter.recentTargetCache.clear();
            }
        }
        FAST_CONSUME_TARGETS.clear();
    }
    int localTargetCountForTests() {
        return cachedTargetsByNpcId.size();
    }
    static int fastConsumeTargetCountForTests() {
        return FAST_CONSUME_TARGETS.size();
    }

    /** Clears all adapter-owned values associated with one removed world. */
    public static void clearWorld(@Nullable String worldName) {
        String normalizedWorld = normalizeWorldName(worldName);
        synchronized (LIVE_ADAPTERS) {
            for (NeedsResourceTargetCacheAdapter adapter : LIVE_ADAPTERS.keySet()) {
                adapter.cachedTargetsByNpcId.entrySet().removeIf(
                        entry -> entry.getValue().worldName().equals(normalizedWorld)
                );
                adapter.recentTargetCache.clearWorld(worldName);
            }
        }
        FAST_CONSUME_TARGETS.entrySet().removeIf(
                entry -> entry.getValue().worldName().equals(normalizedWorld)
        );
        PositionTargetReservationCache.clearWorld(worldName);
    }
    @Nonnull
    private static String normalizeResourceKind(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return "water";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("food")
                || normalized.equals("foodcontainer")
                || normalized.equals("food_container")) {
            return "food_container";
        }
        return "water";
    }
    private static boolean isAutoResourceType(@Nullable String raw) { return raw == null || raw.isBlank() || "auto".equalsIgnoreCase(raw.trim()); }
    @Nonnull
    private static String normalizeWorldName(@Nullable String worldName) {
        return worldName == null || worldName.isBlank() ? "global" : worldName.trim().toLowerCase(Locale.ROOT);
    }
    @Nullable
    private static String resolveWorldName(@Nonnull Store<EntityStore> store) {
        if (store.getExternalData() == null || store.getExternalData().getWorld() == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        return world == null ? null : world.getName();
    }
    private static void rememberFastConsumeTarget(@Nullable UUID npcUuid, @Nullable String worldName,
                                                  @Nullable String resourceKind, @Nullable Vector3d target, long expiresAtMs) {
        if (npcUuid == null || !isFinite(target)) {
            return;
        }
        pruneBounded(
                FAST_CONSUME_TARGETS,
                expiresAtMs - TARGET_CACHE_HIT_TTL_MS,
                MAX_FAST_CONSUME_TARGETS,
                FastConsumeTarget::expiresAtMs
        );
        FAST_CONSUME_TARGETS.put(npcUuid, new FastConsumeTarget(normalizeWorldName(worldName), normalizeResourceKind(resourceKind),
                (int) Math.floor(target.x), (int) Math.floor(target.y), (int) Math.floor(target.z), expiresAtMs));
    }
    private static void clearFastConsumeTarget(@Nullable UUID npcUuid, @Nullable String worldName,
                                               @Nonnull String resourceKind, @Nullable Vector3d target) {
        if (npcUuid == null) {
            return;
        }
        FastConsumeTarget marker = FAST_CONSUME_TARGETS.get(npcUuid);
        if (marker != null && marker.matchesClear(worldName, resourceKind, target)) {
            FAST_CONSUME_TARGETS.remove(npcUuid, marker);
        }
    }
    private record CachedTarget(@Nonnull String worldName,
                                @Nonnull String resourceKind,
                                @Nullable Vector3d target,
                                @Nonnull String reason,
                                double approachRadius,
                                double searchRadius,
                                int verticalRadius,
                                long expiresAtMs,
                                boolean fastConsume) { }
    /** Candidate selection status used by the sensor and focused tests. */
    public record Selection(@Nullable NeedsResourceCandidates.Candidate candidate,
                            boolean allRejected,
                            boolean allReserved,
                            boolean hasRejected,
                            boolean hasReserved,
                            boolean onlyRejectedOrReserved) {
        public Selection(@Nullable NeedsResourceCandidates.Candidate candidate,
                         boolean allRejected,
                         boolean allReserved) {
            this(candidate, allRejected, allReserved, allRejected, allReserved, allRejected || allReserved);
        }
        private static Selection selected(@Nonnull NeedsResourceCandidates.Candidate candidate) { return new Selection(candidate, false, false); }
        private static Selection none() { return new Selection(null, false, false, false, false, false); }
    }
    /** Result returned after local/shared lookup and NPC-specific selection. */
    public record Result(@Nonnull Status status,
                         @Nullable Vector3d target,
                         @Nonnull String reason,
                         double approachRadius,
                         boolean fastConsume,
                         boolean cacheHit,
                         @Nullable NeedsResourceCandidates.Candidate candidate,
                         boolean preflightRequired) {
        private static Result target(@Nonnull Vector3d target, @Nonnull String reason, double approachRadius,
                                     boolean fastConsume, boolean cacheHit,
                                     @Nullable NeedsResourceCandidates.Candidate candidate,
                                     boolean preflightRequired) {
            return new Result(
                    Status.TARGET,
                    target,
                    reason,
                    approachRadius,
                    fastConsume,
                    cacheHit,
                    candidate,
                    preflightRequired
            );
        }
        private static Result miss(@Nonnull String reason, boolean cacheHit) {
            return new Result(Status.MISS, null, reason, 2.0, false, cacheHit, null, false);
        }
        private static Result deferred() {
            return new Result(Status.DEFERRED, null, "resource_search_deferred", 2.0, false, false, null, false);
        }
        private static Result reserved() {
            return new Result(Status.RESERVED, null, "resource_target_reserved", 2.0, false, true, null, false);
        }
    }
    /** Adapter result states. */
    public enum Status {
        TARGET,
        MISS,
        DEFERRED,
        RESERVED
    }
    private record FastConsumeTarget(@Nonnull String worldName, @Nonnull String resourceKind, int x, int y, int z, long expiresAtMs) {
        private boolean matchesWorld(@Nullable String otherWorldName) {
            return worldName.equals(normalizeWorldName(otherWorldName));
        }
        private boolean matches(@Nullable String otherWorldName,
                                @Nonnull String otherResourceKind,
                                @Nullable Vector3d target) {
            return target != null
                    && isFinite(target)
                    && matchesWorld(otherWorldName)
                    && resourceKind.equals(normalizeResourceKind(otherResourceKind))
                    && x == (int) Math.floor(target.x)
                    && y == (int) Math.floor(target.y)
                    && z == (int) Math.floor(target.z);
        }
        private boolean matchesClear(@Nullable String otherWorldName,
                                     @Nonnull String otherResourceKind,
                                     @Nullable Vector3d target) {
            return matchesWorld(otherWorldName)
                    && resourceKind.equals(normalizeResourceKind(otherResourceKind))
                    && (target == null || matches(otherWorldName, otherResourceKind, target));
        }
    }
}
