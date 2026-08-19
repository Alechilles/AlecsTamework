package com.alechilles.alecstamework.npc.progression;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Owns bounded local target state for water and food-container seeks.
 *
 * <p>The owner is shared by all resource sensors. Entries contain normalized
 * identity values, copied coordinates, and scalar search metadata only. No
 * ECS value or mutable vector is retained.</p>
 */
public final class NeedsResourceTargetStateStore {
    public static final int MAX_ENTRIES_PER_RESOURCE = 8_192;
    public static final String WATER = "water";
    public static final String FOOD_CONTAINER = "food_container";

    private static final NeedsResourceTargetStateStore SHARED =
            new NeedsResourceTargetStateStore();

    private final ConcurrentHashMap<UUID, TargetState> waterTargets =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, TargetState> foodContainerTargets =
            new ConcurrentHashMap<>();
    private final Object waterAdmissionLock = new Object();
    private final Object foodContainerAdmissionLock = new Object();
    private final NeedsRecentTargetCache waterRecentTargets =
            new NeedsRecentTargetCache();
    private final NeedsRecentTargetCache foodContainerRecentTargets =
            new NeedsRecentTargetCache();

    /** Creates an independent owner, which is useful for focused behavior tests. */
    public NeedsResourceTargetStateStore() {
    }

    /** Returns the process-wide owner used by resource sensors. */
    @Nonnull
    public static NeedsResourceTargetStateStore shared() {
        return SHARED;
    }

    /** Stores one immutable target state under the NPC/resource key. */
    public void cache(@Nullable UUID npcUuid,
                      @Nullable String worldName,
                      @Nullable String resourceKind,
                      @Nullable Vector3d target,
                      @Nonnull String reason,
                      double approachRadius,
                      double searchRadius,
                      int verticalRadius,
                      long expiresAtMs,
                      boolean fastConsume,
                      @Nonnull PathState pathState) {
        if (npcUuid == null) {
            return;
        }
        String normalizedResource = normalizeResourceKind(resourceKind);
        synchronized (admissionLock(normalizedResource)) {
            ConcurrentHashMap<UUID, TargetState> targets = targetMap(normalizedResource);
            if (!targets.containsKey(npcUuid)) {
                pruneForCapacity(targets);
            }
            targets.put(
                    npcUuid,
                    new TargetState(
                            normalizeWorldName(worldName),
                            normalizedResource,
                            Coordinates.copyOf(target),
                            sanitizeApproachRadius(approachRadius),
                            sanitizeSearchRadius(searchRadius),
                            Math.max(0, verticalRadius),
                            expiresAtMs,
                            reason,
                            fastConsume,
                            pathState
                    )
            );
        }
    }

    /**
     * Returns a target only when identity, expiry, and current search bounds
     * still match. A stale entry is removed by its UUID key.
     */
    @Nullable
    public TargetState resolve(@Nullable UUID npcUuid,
                               @Nullable String worldName,
                               @Nullable String resourceKind,
                               double originX,
                               double originY,
                               double originZ,
                               long nowMs) {
        if (npcUuid == null) {
            return null;
        }
        ConcurrentHashMap<UUID, TargetState> targets = targetMap(resourceKind);
        TargetState state = targets.get(npcUuid);
        if (state == null) {
            return null;
        }
        String normalizedWorld = normalizeWorldName(worldName);
        String normalizedResource = normalizeResourceKind(resourceKind);
        if (!state.worldName().equals(normalizedWorld)
                || !state.resourceKind().equals(normalizedResource)
                || nowMs >= state.expiresAtMs()
                || (state.target() != null && !isUsable(
                        state.target(), originX, originY, originZ,
                        state.searchRadius(), state.verticalRadius()))) {
            targets.remove(npcUuid, state);
            return null;
        }
        return state;
    }

    /** Promotes a matching pending target after a successful path preflight. */
    public boolean promote(@Nullable UUID npcUuid,
                           @Nullable String worldName,
                           @Nullable String resourceKind,
                           @Nullable Vector3d target) {
        if (npcUuid == null || target == null || !isFinite(target)) {
            return false;
        }
        ConcurrentHashMap<UUID, TargetState> targets = targetMap(resourceKind);
        TargetState state = targets.get(npcUuid);
        if (state == null
                || state.pathState() != PathState.PENDING
                || !state.worldName().equals(normalizeWorldName(worldName))
                || !state.resourceKind().equals(normalizeResourceKind(resourceKind))
                || !sameBlock(state.target(), target)) {
            return false;
        }
        return targets.replace(npcUuid, state, state.withPathState(PathState.VALIDATED));
    }

    /** Extends a matching pending target while a non-terminal preflight runs. */
    public boolean keepPending(@Nullable UUID npcUuid,
                               @Nullable String worldName,
                               @Nullable String resourceKind,
                               @Nullable Vector3d target,
                               long nowMs,
                               long ttlMs) {
        if (npcUuid == null || target == null || !isFinite(target)) {
            return false;
        }
        String normalizedResource = normalizeResourceKind(resourceKind);
        synchronized (admissionLock(normalizedResource)) {
            ConcurrentHashMap<UUID, TargetState> targets = targetMap(normalizedResource);
            TargetState state = targets.get(npcUuid);
            if (state == null
                    || state.pathState() != PathState.PENDING
                    || !state.worldName().equals(normalizeWorldName(worldName))
                    || !state.resourceKind().equals(normalizedResource)
                    || !sameBlock(state.target(), target)) {
                return false;
            }
            return targets.replace(
                    npcUuid,
                    state,
                    state.withExpiresAt(nowMs + Math.max(1L, ttlMs))
            );
        }
    }

    /**
     * Removes one matching UUID entry. A rejection can request any-world
     * matching because its source event may not carry world identity.
     */
    public boolean clear(@Nullable UUID npcUuid,
                         @Nullable String worldName,
                         @Nullable String resourceKind,
                         @Nullable Vector3d target,
                         boolean anyWorld) {
        if (npcUuid == null) {
            return false;
        }
        ConcurrentHashMap<UUID, TargetState> targets = targetMap(resourceKind);
        TargetState state = targets.get(npcUuid);
        if (state == null
                || (!anyWorld && !state.worldName().equals(normalizeWorldName(worldName)))
                || (target != null && !sameBlock(state.target(), target))) {
            return false;
        }
        return targets.remove(npcUuid, state);
    }

    /** Removes all target and recent state owned by one world. */
    public void clearWorld(@Nullable String worldName) {
        String normalizedWorld = normalizeWorldName(worldName);
        synchronized (waterAdmissionLock) {
            waterTargets.entrySet().removeIf(entry -> entry.getValue().worldName().equals(normalizedWorld));
            waterRecentTargets.clearWorld(worldName);
        }
        synchronized (foodContainerAdmissionLock) {
            foodContainerTargets.entrySet().removeIf(entry -> entry.getValue().worldName().equals(normalizedWorld));
            foodContainerRecentTargets.clearWorld(worldName);
        }
    }

    /** Clears all target and recent state for lifecycle teardown or tests. */
    public void clear() {
        synchronized (waterAdmissionLock) {
            waterTargets.clear();
            waterRecentTargets.clear();
        }
        synchronized (foodContainerAdmissionLock) {
            foodContainerTargets.clear();
            foodContainerRecentTargets.clear();
        }
    }

    /** Returns the number of entries in one normalized resource map. */
    public int size(@Nullable String resourceKind) {
        return targetMap(resourceKind).size();
    }

    /** Remembers a recent source in the resource-specific bounded cache. */
    public void rememberRecentTarget(@Nullable UUID npcUuid,
                                     @Nullable String worldName,
                                     @Nullable String resourceKind,
                                     @Nullable Vector3d target,
                                     long nowMs) {
        String normalizedResource = normalizeResourceKind(resourceKind);
        synchronized (admissionLock(normalizedResource)) {
            recentTargetCache(normalizedResource).remember(npcUuid, worldName, target, nowMs);
        }
    }

    /** Resolves a recent source from the resource-specific bounded cache. */
    @Nullable
    public Vector3d resolveRecentTarget(@Nullable UUID npcUuid,
                                        @Nullable String worldName,
                                        @Nullable String resourceKind,
                                        @Nullable Vector3d currentPosition,
                                        long nowMs) {
        return recentTargetCache(resourceKind).resolve(npcUuid, worldName, currentPosition, nowMs);
    }

    /** Forgets a recent source in the resource-specific bounded cache. */
    public void forgetRecentTarget(@Nullable UUID npcUuid,
                                   @Nullable String worldName,
                                   @Nullable String resourceKind,
                                   @Nullable Vector3d target) {
        String normalizedResource = normalizeResourceKind(resourceKind);
        synchronized (admissionLock(normalizedResource)) {
            recentTargetCache(normalizedResource).forget(npcUuid, worldName, target);
        }
    }

    /** Returns the number of entries in one centralized recent-target cache. */
    public int recentSize(@Nullable String resourceKind) {
        String normalizedResource = normalizeResourceKind(resourceKind);
        synchronized (admissionLock(normalizedResource)) {
            return recentTargetCache(normalizedResource).countForTests();
        }
    }

    @Nonnull
    private ConcurrentHashMap<UUID, TargetState> targetMap(@Nullable String resourceKind) {
        return FOOD_CONTAINER.equals(normalizeResourceKind(resourceKind))
                ? foodContainerTargets
                : waterTargets;
    }

    @Nonnull
    private NeedsRecentTargetCache recentTargetCache(@Nullable String resourceKind) {
        return FOOD_CONTAINER.equals(normalizeResourceKind(resourceKind))
                ? foodContainerRecentTargets
                : waterRecentTargets;
    }

    @Nonnull
    private Object admissionLock(@Nullable String resourceKind) {
        return FOOD_CONTAINER.equals(normalizeResourceKind(resourceKind))
                ? foodContainerAdmissionLock
                : waterAdmissionLock;
    }

    private static void pruneForCapacity(@Nonnull ConcurrentHashMap<UUID, TargetState> targets) {
        while (targets.size() >= MAX_ENTRIES_PER_RESOURCE) {
            var keys = targets.keySet().iterator();
            if (!keys.hasNext() || targets.remove(keys.next()) == null) {
                return;
            }
        }
    }

    private static boolean isUsable(@Nonnull Coordinates target,
                                    double originX,
                                    double originY,
                                    double originZ,
                                    double radius,
                                    int verticalRadius) {
        if (!Double.isFinite(originX)
                || !Double.isFinite(originY)
                || !Double.isFinite(originZ)
                || !Double.isFinite(radius)
                || radius <= 0.0) {
            return false;
        }
        double dx = target.x() - originX;
        double dz = target.z() - originZ;
        return (dx * dx) + (dz * dz) <= (radius * radius) + 0.000001
                && Math.abs(Math.floor(target.y()) - Math.floor(originY)) <= Math.max(0, verticalRadius);
    }

    private static boolean sameBlock(@Nullable Coordinates first, @Nullable Vector3d second) {
        return first != null
                && second != null
                && isFinite(second)
                && (int) Math.floor(first.x()) == (int) Math.floor(second.x)
                && (int) Math.floor(first.y()) == (int) Math.floor(second.y)
                && (int) Math.floor(first.z()) == (int) Math.floor(second.z);
    }

    private static boolean isFinite(@Nullable Vector3d vector) {
        return vector != null
                && Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }

    private static double sanitizeApproachRadius(double value) {
        return Double.isFinite(value) && value > 0.000001 ? value : 2.0;
    }

    private static double sanitizeSearchRadius(double value) {
        return Double.isFinite(value) && value > 0.000001 ? value : 12.0;
    }

    @Nonnull
    private static String normalizeResourceKind(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return WATER;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("food")
                || normalized.equals("foodcontainer")
                || normalized.equals(FOOD_CONTAINER)
                ? FOOD_CONTAINER
                : WATER;
    }

    @Nonnull
    private static String normalizeWorldName(@Nullable String worldName) {
        return worldName == null || worldName.isBlank()
                ? "global"
                : worldName.trim().toLowerCase(Locale.ROOT);
    }

    /** Distinguishes a target that still needs path preflight from one that passed it. */
    public enum PathState {
        PENDING,
        VALIDATED
    }

    /** Immutable scalar target state retained by the shared owner. */
    public record TargetState(@Nonnull String worldName,
                              @Nonnull String resourceKind,
                              @Nullable Coordinates target,
                              double approachRadius,
                              double searchRadius,
                              int verticalRadius,
                              long expiresAtMs,
                              @Nonnull String reason,
                              boolean fastConsume,
                              @Nonnull PathState pathState) {
        private TargetState withPathState(@Nonnull PathState nextPathState) {
            return with(nextPathState, expiresAtMs);
        }

        private TargetState withExpiresAt(long nextExpiresAtMs) {
            return with(pathState, nextExpiresAtMs);
        }

        @Nonnull
        private TargetState with(@Nonnull PathState nextPathState, long nextExpiresAtMs) {
            return new TargetState(
                    worldName,
                    resourceKind,
                    target,
                    approachRadius,
                    searchRadius,
                    verticalRadius,
                    nextExpiresAtMs,
                    reason,
                    fastConsume,
                    nextPathState
            );
        }
    }

    /** Copied target coordinates that do not retain a mutable ECS vector. */
    public record Coordinates(double x, double y, double z) {
        @Nullable
        private static Coordinates copyOf(@Nullable Vector3d vector) {
            return isFinite(vector) ? new Coordinates(vector.x, vector.y, vector.z) : null;
        }

        @Nonnull
        public Vector3d toVector() {
            return new Vector3d(x, y, z);
        }
    }
}
