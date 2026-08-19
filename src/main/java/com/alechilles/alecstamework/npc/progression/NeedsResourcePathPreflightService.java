package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.performance.RuntimePressureDomain;
import com.alechilles.alecstamework.performance.TameworkRuntimePressureService;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.movement.constraints.RelaxedConstraint;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.movement.controllers.ProbeMoveData;
import com.hypixel.hytale.server.npc.navigation.AStarBase;
import com.hypixel.hytale.server.npc.navigation.AStarNodePoolProviderSimple;
import com.hypixel.hytale.server.npc.navigation.AStarWithTarget;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Performs a bounded, cache-backed pathfinder preflight for needs resource targets before
 * the NPC is allowed to enter its resource-seek movement state.
 */
public final class NeedsResourcePathPreflightService {
    static final int MAX_NODES_PER_SENSOR_PASS = 32;
    static final int MAX_TOTAL_NODES = 256;
    static final int MAX_OPEN_NODES = 128;
    static final int MAX_PATH_LENGTH = 128;
    static final int PRECHECK_CACHE_MAX_ENTRIES = 4096;
    static final long READY_TTL_MS = 1_500L;
    static final long NO_PATH_TTL_MS = 8_000L;
    static final long COMPUTING_TTL_MS = 5_000L;
    private static final int MAX_GLOBAL_NODES_PER_50MS = 512;
    private static final long GLOBAL_BUDGET_WINDOW_MS = 50L;
    private static final double DEFAULT_STOP_DISTANCE = 2.0;
    private static final double EPSILON = 0.000001;
    private static final NeedsResourcePathPreflightService SHARED =
            new NeedsResourcePathPreflightService();
    private static final AtomicLong budgetWindowMs = new AtomicLong();
    private static final AtomicInteger budgetUsedNodes = new AtomicInteger();

    private final ConcurrentHashMap<PreflightKey, CachedPreflight> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RecentReadyKey, RecentReadyPreflight> recentReadyTargets =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AuthorityKey, AuthorityState> authorityStates =
            new ConcurrentHashMap<>();
    private final Object stateLock = new Object();

    /** Returns the process-wide preflight owner shared by all resource sensors. */
    @Nonnull
    public static NeedsResourcePathPreflightService shared() {
        return SHARED;
    }

    @Nonnull
    public PathPreflightResult preflight(@Nonnull Ref<EntityStore> ref,
                                         @Nonnull Role role,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull UUID npcUuid,
                                         @Nonnull String resourceType,
                                         @Nullable Vector3d target,
                                         long nowMs) {
        return preflight(ref, role, store, npcUuid, resourceType, target, DEFAULT_STOP_DISTANCE, nowMs);
    }

    @Nonnull
    public PathPreflightResult preflight(@Nonnull Ref<EntityStore> ref,
                                         @Nonnull Role role,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull UUID npcUuid,
                                         @Nonnull String resourceType,
                                         @Nullable Vector3d target,
                                         double stopDistance,
                                         long nowMs) {
        if (target == null || !isFinite(target)) {
            return PathPreflightResult.unavailable("path_preflight_target_invalid");
        }
        double effectiveStopDistance = sanitizeStopDistance(stopDistance);
        MotionController motionController = role.getActiveMotionController();
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        BoundingBox boundingBox = store.getComponent(ref, BoundingBox.getComponentType());
        AStarNodePoolProviderSimple nodePoolProvider = store.getResource(AStarNodePoolProviderSimple.getResourceType());
        if (motionController == null || transform == null || transform.getPosition() == null) {
            return PathPreflightResult.unavailable("path_preflight_motion_context_missing");
        }
        if (boundingBox == null || boundingBox.getBoundingBox() == null || nodePoolProvider == null) {
            return PathPreflightResult.unavailable("path_preflight_path_context_missing");
        }
        Vector3d start = transform.getPosition();
        if (!isFinite(start)) {
            return PathPreflightResult.unavailable("path_preflight_start_invalid");
        }
        PreflightKey key = PreflightKey.from(
                npcUuid,
                resolveWorldName(store),
                resourceType,
                motionController.getType(),
                start,
                target,
                effectiveStopDistance
        );
        if (key == null) {
            return PathPreflightResult.unavailable("path_preflight_key_unavailable");
        }
        if (isWithinStopDistance(start, target, effectiveStopDistance, motionController)) {
            return publishImmediateReady(key, "path_preflight_already_in_range", nowMs);
        }
        return preflight(
                key,
                () -> createPathComputation(
                        ref,
                        store,
                        motionController,
                        start,
                        target,
                        effectiveStopDistance,
                        boundingBox.getBoundingBox(),
                        nodePoolProvider
                ),
                nowMs
        );
    }

    @Nonnull
    private PathPreflightResult publishImmediateReady(@Nonnull PreflightKey key,
                                                       @Nonnull String reason,
                                                       long nowMs) {
        synchronized (stateLock) {
            ComputationOperation operation = registerOperationLocked(key);
            if (!isCurrentOperationLocked(operation)) {
                clearOperationLocked(operation);
                return PathPreflightResult.unavailable("path_preflight_invalidated");
            }
            cacheTerminalResultLocked(key, PathPreflightStatus.READY, reason, nowMs);
            removeOperationLocked(operation);
            pruneAuthorityStatesLocked();
            return PathPreflightResult.ready(reason);
        }
    }

    @Nonnull
    PathPreflightResult preflight(@Nonnull PreflightKey key,
                                  @Nonnull PathComputationFactory computationFactory,
                                  long nowMs) {
        ComputationOperation operation;
        boolean createComputation = false;
        synchronized (stateLock) {
            PathPreflightResult recentReady = resolveRecentReady(key, nowMs);
            if (recentReady != null) {
                return recentReady;
            }
            CachedPreflight cached = cache.get(key);
            if (cached != null && nowMs < cached.expiresAtMs()) {
                if (cached.status() == PathPreflightStatus.READY) {
                    return PathPreflightResult.ready(cached.reason());
                }
                if (cached.status() == PathPreflightStatus.NO_PATH) {
                    return PathPreflightResult.noPath(cached.reason());
                }
                operation = cached.operation();
                if (isCurrentOperationLocked(operation)) {
                    if (operation.computing) {
                        return PathPreflightResult.computing("path_preflight_computing");
                    }
                } else {
                    removeEntryLocked(key, cached);
                    operation = null;
                }
            } else {
                if (cached != null) {
                    removeEntryLocked(key, cached);
                }
                operation = null;
            }
            if (operation == null) {
                operation = findActiveOperationLocked(key);
            }
            if (operation == null) {
                operation = registerOperationLocked(key);
                createComputation = true;
            } else if (operation.computation == null) {
                return PathPreflightResult.computing("path_preflight_computing");
            }
        }

        if (createComputation) {
            PathComputation computation;
            try {
                computation = computationFactory.create();
            } catch (RuntimeException failure) {
                synchronized (stateLock) {
                    cancelOperationLocked(operation);
                }
                throw failure;
            }
            synchronized (stateLock) {
                operation.computation = computation;
                if (!isCurrentOperationLocked(operation)) {
                    clearOperationLocked(operation);
                    return PathPreflightResult.unavailable("path_preflight_invalidated");
                }
                if (computation == null) {
                    clearOperationLocked(operation);
                    return PathPreflightResult.unavailable("path_preflight_create_failed");
                }
            }
        }
        return runOperation(operation, nowMs);
    }

    @Nonnull
    private PathPreflightResult runOperation(@Nonnull ComputationOperation operation, long nowMs) {
        int budget;
        synchronized (stateLock) {
            if (!isCurrentOperationLocked(operation) || operation.computation == null) {
                clearOperationLocked(operation);
                return PathPreflightResult.unavailable("path_preflight_invalidated");
            }
            if (operation.computing) {
                return PathPreflightResult.computing("path_preflight_computing");
            }
            budget = claimGlobalBudget(nowMs, MAX_NODES_PER_SENSOR_PASS);
            if (budget <= 0) {
                cacheComputingLocked(
                        operation.key,
                        operation,
                        "path_preflight_budget_deferred",
                        nowMs
                );
                return isCurrentOperationLocked(operation)
                        ? PathPreflightResult.computing("path_preflight_budget_deferred")
                        : PathPreflightResult.unavailable("path_preflight_invalidated");
            }
            operation.computing = true;
        }

        PathPreflightStatus status;
        boolean failed = false;
        long startedNs = System.nanoTime();
        try {
            status = operation.computation.compute(budget);
        } catch (RuntimeException ignored) {
            failed = true;
            status = PathPreflightStatus.NO_PATH;
        }
        recordPathPreflightWork(startedNs, nowMs);

        synchronized (stateLock) {
            operation.computing = false;
            if (!isCurrentOperationLocked(operation)) {
                clearOperationLocked(operation);
                return PathPreflightResult.unavailable("path_preflight_invalidated");
            }
            if (failed) {
                clearOperationLocked(operation);
                cacheTerminalResultLocked(
                        operation.key,
                        PathPreflightStatus.NO_PATH,
                        "path_preflight_exception",
                        nowMs
                );
                pruneAuthorityStatesLocked();
                return PathPreflightResult.noPath("path_preflight_exception");
            }
            if (status == PathPreflightStatus.READY) {
                clearOperationLocked(operation);
                cacheTerminalResultLocked(
                        operation.key,
                        PathPreflightStatus.READY,
                        "path_preflight_ready",
                        nowMs
                );
                cacheRecentReadyLocked(operation.key, nowMs);
                pruneAuthorityStatesLocked();
                return PathPreflightResult.ready("path_preflight_ready");
            }
            if (status == PathPreflightStatus.NO_PATH) {
                clearOperationLocked(operation);
                cacheTerminalResultLocked(
                        operation.key,
                        PathPreflightStatus.NO_PATH,
                        "path_preflight_no_path",
                        nowMs
                );
                pruneAuthorityStatesLocked();
                return PathPreflightResult.noPath("path_preflight_no_path");
            }
            cacheComputingLocked(operation.key, operation, "path_preflight_computing", nowMs);
            return isCurrentOperationLocked(operation)
                    ? PathPreflightResult.computing("path_preflight_computing")
                    : PathPreflightResult.unavailable("path_preflight_invalidated");
        }
    }

    void clearForTests() {
        synchronized (stateLock) {
            for (AuthorityState state : authorityStates.values()) {
                for (ComputationOperation operation : new ArrayList<>(state.operations)) {
                    cancelOperationLocked(operation);
                }
            }
            for (CachedPreflight value : cache.values()) {
                if (value != null && value.operation() != null) {
                    cancelOperationLocked(value.operation());
                }
            }
            cache.clear();
            recentReadyTargets.clear();
            pruneAuthorityStatesLocked();
        }
        budgetWindowMs.set(0L);
        budgetUsedNodes.set(0);
    }

    int cacheSizeForTests() {
        return cache.size();
    }

    int recentReadySizeForTests() {
        return recentReadyTargets.size();
    }

    /** Removes all entries for one NPC, resource, and target block authority. */
    public void invalidateTarget(@Nullable UUID npcUuid,
                                @Nullable String worldName,
                                @Nullable String resourceType,
                                @Nullable Vector3d target) {
        if (npcUuid == null || resourceType == null || resourceType.isBlank()
                || target == null || !isFinite(target)) {
            return;
        }
        String normalizedResource = normalizeResourceType(resourceType);
        String normalizedWorld = normalizeWorldNameForMatch(worldName);
        int targetX = (int) Math.floor(target.x);
        int targetY = (int) Math.floor(target.y);
        int targetZ = (int) Math.floor(target.z);
        synchronized (stateLock) {
            for (var stateEntry : authorityStates.entrySet()) {
                if (matchesTargetAuthority(
                        stateEntry.getKey(), npcUuid, normalizedWorld, normalizedResource,
                        targetX, targetY, targetZ
                )) {
                    cancelAuthorityStateLocked(stateEntry.getValue());
                }
            }
            for (var entry : cache.entrySet()) {
                CachedPreflight value = entry.getValue();
                if (value == null || !matchesTargetAuthority(
                        entry.getKey(), npcUuid, normalizedWorld, normalizedResource,
                        targetX, targetY, targetZ)) {
                    continue;
                }
                if (cache.remove(entry.getKey(), value)) {
                    cancelOperationLocked(value.operation());
                }
            }
            for (var entry : recentReadyTargets.entrySet()) {
                RecentReadyPreflight value = entry.getValue();
                if (value == null || !matchesTargetAuthority(
                        value.key(), npcUuid, normalizedWorld, normalizedResource,
                        targetX, targetY, targetZ)) {
                    continue;
                }
                recentReadyTargets.remove(entry.getKey(), value);
            }
            pruneAuthorityStatesLocked();
        }
    }

    /** Removes all cache and lease entries for one normalized world. */
    public void clearWorld(@Nullable String worldName) {
        String normalizedWorld = normalizeWorldNameForMatch(worldName);
        if (normalizedWorld == null) {
            return;
        }
        synchronized (stateLock) {
            for (var stateEntry : authorityStates.entrySet()) {
                if (normalizedWorld.equals(stateEntry.getKey().worldName())) {
                    cancelAuthorityStateLocked(stateEntry.getValue());
                }
            }
            for (var entry : cache.entrySet()) {
                CachedPreflight value = entry.getValue();
                if (value == null || !normalizedWorld.equals(entry.getKey().worldName())) {
                    continue;
                }
                if (cache.remove(entry.getKey(), value)) {
                    cancelOperationLocked(value.operation());
                }
            }
            for (var entry : recentReadyTargets.entrySet()) {
                RecentReadyPreflight value = entry.getValue();
                if (value != null && normalizedWorld.equals(entry.getKey().worldName())) {
                    recentReadyTargets.remove(entry.getKey(), value);
                }
            }
            pruneAuthorityStatesLocked();
        }
    }

    @Nonnull
    private ComputationOperation registerOperationLocked(@Nonnull PreflightKey key) {
        AuthorityKey authority = AuthorityKey.from(key);
        AuthorityState state = authorityStates.computeIfAbsent(authority, ignored -> new AuthorityState());
        ComputationOperation operation = new ComputationOperation(key, authority, state.generation);
        state.operations.add(operation);
        return operation;
    }

    @Nullable
    private ComputationOperation findActiveOperationLocked(@Nonnull PreflightKey key) {
        AuthorityState state = authorityStates.get(AuthorityKey.from(key));
        if (state == null) {
            return null;
        }
        for (ComputationOperation operation : state.operations) {
            if (operation.key.equals(key) && isCurrentOperationLocked(operation)) {
                return operation;
            }
        }
        return null;
    }

    private boolean isCurrentOperationLocked(@Nullable ComputationOperation operation) {
        if (operation == null || operation.cancelled) {
            return false;
        }
        AuthorityState state = authorityStates.get(operation.authority);
        return state != null
                && state.generation == operation.generation
                && state.operations.contains(operation);
    }

    private void cancelAuthorityStateLocked(@Nonnull AuthorityState state) {
        state.generation++;
        for (ComputationOperation operation : new ArrayList<>(state.operations)) {
            cancelOperationLocked(operation);
        }
    }

    private void cancelOperationLocked(@Nullable ComputationOperation operation) {
        if (operation == null) {
            return;
        }
        operation.cancelled = true;
        if (!operation.computing) {
            clearOperationLocked(operation);
        }
    }

    private void clearOperationLocked(@Nonnull ComputationOperation operation) {
        if (!operation.cleared && operation.computation != null) {
            clearComputation(operation.computation);
            operation.cleared = true;
        }
        removeOperationLocked(operation);
    }

    private void removeOperationLocked(@Nonnull ComputationOperation operation) {
        AuthorityState state = authorityStates.get(operation.authority);
        if (state != null
                && state.operations.remove(operation)
                && state.operations.isEmpty()) {
            authorityStates.remove(operation.authority, state);
        }
    }

    private void pruneAuthorityStatesLocked() {
        authorityStates.entrySet().removeIf(entry -> entry.getValue().operations.isEmpty());
    }

    private PathComputation createPathComputation(@Nonnull Ref<EntityStore> ref,
                                                  @Nonnull Store<EntityStore> store,
                                                  @Nonnull MotionController motionController,
                                                  @Nonnull Vector3d start,
                                                  @Nonnull Vector3d target,
                                                  double stopDistance,
                                                  @Nonnull Box selfBoundingBox,
                                                  @Nonnull AStarNodePoolProviderSimple nodePoolProvider) {
        AStarWithTarget aStar = new AStarWithTarget();
        aStar.setCanMoveDiagonal(true);
        aStar.setOptimizedBuildPath(true);
        aStar.setMaxPathLength(MAX_PATH_LENGTH);
        aStar.setOpenNodesLimit(MAX_OPEN_NODES);
        aStar.setTotalNodesLimit(MAX_TOTAL_NODES);

        ProbeMoveData probeMoveData = new ProbeMoveData();
        probeMoveData.setRelaxedConstraints(EnumSet.noneOf(RelaxedConstraint.class));
        return new HytalePathComputation(
                ref,
                store,
                motionController,
                aStar,
                new Vector3d(start),
                new Vector3d(target),
                new NeedsSeekPathEvaluator(target, selfBoundingBox, stopDistance),
                probeMoveData,
                nodePoolProvider
        );
    }

    private void cacheComputingLocked(@Nonnull PreflightKey key,
                                      @Nonnull ComputationOperation operation,
                                      @Nonnull String reason,
                                      long nowMs) {
        replaceCacheEntryLocked(
                key,
                new CachedPreflight(
                        PathPreflightStatus.COMPUTING,
                        reason,
                        nowMs + COMPUTING_TTL_MS,
                        operation
                )
        );
        cleanupCacheLocked(nowMs);
    }

    private void cacheTerminalResultLocked(@Nonnull PreflightKey key,
                                           @Nonnull PathPreflightStatus status,
                                           @Nonnull String reason,
                                           long nowMs) {
        replaceCacheEntryLocked(
                key,
                new CachedPreflight(status, reason, nowMs + terminalTtlMs(status, nowMs), null)
        );
        cleanupCacheLocked(nowMs);
    }

    private void replaceCacheEntryLocked(@Nonnull PreflightKey key,
                                         @Nonnull CachedPreflight replacement) {
        CachedPreflight previous = cache.put(key, replacement);
        if (previous != null && previous.operation() != replacement.operation()) {
            cancelOperationLocked(previous.operation());
        }
    }

    private static long terminalTtlMs(@Nonnull PathPreflightStatus status) {
        return terminalTtlMs(status, System.currentTimeMillis());
    }

    private static long terminalTtlMs(@Nonnull PathPreflightStatus status, long nowMs) {
        long baseTtlMs = status == PathPreflightStatus.READY ? READY_TTL_MS : NO_PATH_TTL_MS;
        if (status == PathPreflightStatus.READY) {
            return baseTtlMs;
        }
        return TameworkRuntimePressureService.getInstance().scaleTtlMs(
                RuntimePressureDomain.NEEDS_PATH_PREFLIGHT,
                baseTtlMs,
                nowMs
        );
    }

    static long terminalTtlMsForTests(@Nonnull PathPreflightStatus status) {
        return terminalTtlMs(status);
    }

    private static void recordPathPreflightWork(long startedNs, long nowMs) {
        long elapsedNs = Math.max(0L, System.nanoTime() - startedNs);
        TameworkRuntimePressureService.getInstance().recordWork(
                RuntimePressureDomain.NEEDS_PATH_PREFLIGHT,
                elapsedNs,
                nowMs
        );
    }

    private void cleanupCacheLocked(long nowMs) {
        if (cache.size() < PRECHECK_CACHE_MAX_ENTRIES) {
            return;
        }
        cache.entrySet().removeIf(entry -> {
            CachedPreflight value = entry == null ? null : entry.getValue();
            boolean remove = entry == null || entry.getKey() == null || value == null || nowMs >= value.expiresAtMs();
            if (remove && value != null) {
                cancelOperationLocked(value.operation());
            }
            return remove;
        });
        int excess = cache.size() - PRECHECK_CACHE_MAX_ENTRIES;
        if (excess <= 0) {
            pruneAuthorityStatesLocked();
            return;
        }
        for (PreflightKey key : cache.keySet()) {
            if (excess <= 0) {
                pruneAuthorityStatesLocked();
                return;
            }
            CachedPreflight removed = cache.remove(key);
            if (removed != null) {
                cancelOperationLocked(removed.operation());
                excess--;
            }
        }
        pruneAuthorityStatesLocked();
    }

    private void removeEntryLocked(@Nonnull PreflightKey key, @Nonnull CachedPreflight value) {
        if (cache.remove(key, value)) {
            cancelOperationLocked(value.operation());
        }
    }

    private static void clearComputation(@Nullable PathComputation computation) {
        if (computation != null) {
            computation.clear();
        }
    }

    private static int claimGlobalBudget(long nowMs, int requestedNodes) {
        long window = nowMs - Math.floorMod(nowMs, GLOBAL_BUDGET_WINDOW_MS);
        long currentWindow = budgetWindowMs.get();
        if (currentWindow != window && budgetWindowMs.compareAndSet(currentWindow, window)) {
            budgetUsedNodes.set(0);
        }
        while (true) {
            int used = budgetUsedNodes.get();
            int available = MAX_GLOBAL_NODES_PER_50MS - used;
            if (available <= 0) {
                return 0;
            }
            int claim = Math.min(requestedNodes, available);
            if (budgetUsedNodes.compareAndSet(used, used + claim)) {
                return claim;
            }
        }
    }

    @Nullable
    private static String resolveWorldName(@Nonnull Store<EntityStore> store) {
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        String worldName = world != null ? world.getName() : null;
        return worldName == null || worldName.isBlank() ? null : worldName;
    }

    private static boolean isWithinStopDistance(@Nonnull Vector3d start,
                                                @Nonnull Vector3d target,
                                                double stopDistance,
                                                @Nonnull MotionController motionController) {
        double effectiveStopDistance = sanitizeStopDistance(stopDistance);
        double distanceSquared = motionController.waypointDistanceSquared(start, target);
        return Double.isFinite(distanceSquared)
                && distanceSquared <= (effectiveStopDistance * effectiveStopDistance) + EPSILON;
    }

    private static double sanitizeStopDistance(double stopDistance) {
        return Double.isFinite(stopDistance) && stopDistance > 0.0 ? stopDistance : DEFAULT_STOP_DISTANCE;
    }

    private static boolean isFinite(@Nonnull Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    @Nonnull
    private static String normalizeResourceType(@Nonnull String resourceType) {
        String normalized = resourceType.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "<unknown>" : normalized;
    }

    @Nullable
    private static String normalizeWorldNameForMatch(@Nullable String worldName) {
        return worldName == null || worldName.isBlank()
                ? null
                : worldName.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matchesTargetAuthority(@Nonnull PreflightKey key,
                                                   @Nonnull UUID npcUuid,
                                                   @Nullable String worldName,
                                                   @Nonnull String resourceType,
                                                   int targetX,
                                                   int targetY,
                                                   int targetZ) {
        return matchesTargetAuthority(
                AuthorityKey.from(key),
                npcUuid,
                worldName,
                resourceType,
                targetX,
                targetY,
                targetZ
        );
    }

    private static boolean matchesTargetAuthority(@Nonnull AuthorityKey key,
                                                   @Nonnull UUID npcUuid,
                                                   @Nullable String worldName,
                                                   @Nonnull String resourceType,
                                                   int targetX,
                                                   int targetY,
                                                   int targetZ) {
        return key.npcUuid().equals(npcUuid)
                && (worldName == null || worldName.equals(key.worldName()))
                && resourceType.equals(key.resourceType())
                && key.targetX() == targetX
                && key.targetY() == targetY
                && key.targetZ() == targetZ;
    }

    public enum PathPreflightStatus {
        READY,
        COMPUTING,
        NO_PATH,
        UNAVAILABLE
    }

    public record PathPreflightResult(@Nonnull PathPreflightStatus status,
                                      @Nonnull String reason) {
        @Nonnull
        public static PathPreflightResult ready(@Nonnull String reason) {
            return new PathPreflightResult(PathPreflightStatus.READY, reason);
        }

        @Nonnull
        public static PathPreflightResult computing(@Nonnull String reason) {
            return new PathPreflightResult(PathPreflightStatus.COMPUTING, reason);
        }

        @Nonnull
        public static PathPreflightResult noPath(@Nonnull String reason) {
            return new PathPreflightResult(PathPreflightStatus.NO_PATH, reason);
        }

        @Nonnull
        public static PathPreflightResult unavailable(@Nonnull String reason) {
            return new PathPreflightResult(PathPreflightStatus.UNAVAILABLE, reason);
        }

        public boolean ready() {
            return status == PathPreflightStatus.READY;
        }

        public boolean computing() {
            return status == PathPreflightStatus.COMPUTING;
        }

        public boolean noPath() {
            return status == PathPreflightStatus.NO_PATH;
        }
    }

    record PreflightKey(@Nonnull UUID npcUuid,
                        @Nonnull String worldName,
                        @Nonnull String resourceType,
                        @Nonnull String motionControllerType,
                        int startX,
                        int startY,
                        int startZ,
                        int targetX,
                        int targetY,
                        int targetZ,
                        int stopDistanceKey) {
        @Nullable
        static PreflightKey from(@Nonnull UUID npcUuid,
                                 @Nullable String worldName,
                                 @Nonnull String resourceType,
                                 @Nullable String motionControllerType,
                                 @Nonnull Vector3d start,
                                 @Nonnull Vector3d target) {
            return from(npcUuid, worldName, resourceType, motionControllerType, start, target, DEFAULT_STOP_DISTANCE);
        }

        @Nullable
        static PreflightKey from(@Nonnull UUID npcUuid,
                                 @Nullable String worldName,
                                 @Nonnull String resourceType,
                                 @Nullable String motionControllerType,
                                 @Nonnull Vector3d start,
                                 @Nonnull Vector3d target,
                                 double stopDistance) {
            if (worldName == null || worldName.isBlank()) {
                return null;
            }
            String normalizedResource = resourceType.trim().toLowerCase(Locale.ROOT);
            String normalizedMotion = motionControllerType == null || motionControllerType.isBlank()
                    ? "<unknown>"
                    : motionControllerType.trim().toLowerCase(Locale.ROOT);
            return new PreflightKey(
                    npcUuid,
                    worldName.trim().toLowerCase(Locale.ROOT),
                    normalizedResource.isBlank() ? "<unknown>" : normalizedResource,
                    normalizedMotion,
                    (int) Math.floor(start.x),
                    (int) Math.floor(start.y),
                    (int) Math.floor(start.z),
                    (int) Math.floor(target.x),
                    (int) Math.floor(target.y),
                    (int) Math.floor(target.z),
                    (int) Math.ceil(sanitizeStopDistance(stopDistance) * 10.0)
            );
        }
    }

    @FunctionalInterface
    interface PathComputationFactory {
        @Nullable
        PathComputation create();
    }

    interface PathComputation {
        @Nonnull
        PathPreflightStatus compute(int maxNodes);

        void clear();
    }

    private record AuthorityKey(@Nonnull UUID npcUuid,
                                @Nonnull String worldName,
                                @Nonnull String resourceType,
                                @Nonnull String motionControllerType,
                                int targetX,
                                int targetY,
                                int targetZ,
                                int stopDistanceKey) {
        @Nonnull
        static AuthorityKey from(@Nonnull PreflightKey key) {
            return new AuthorityKey(
                    key.npcUuid(),
                    key.worldName(),
                    key.resourceType(),
                    key.motionControllerType(),
                    key.targetX(),
                    key.targetY(),
                    key.targetZ(),
                    key.stopDistanceKey()
            );
        }
    }

    private static final class AuthorityState {
        private long generation;
        private final Set<ComputationOperation> operations = new HashSet<>();
    }

    private static final class ComputationOperation {
        private final PreflightKey key;
        private final AuthorityKey authority;
        private final long generation;
        @Nullable
        private PathComputation computation;
        private boolean computing;
        private boolean cancelled;
        private boolean cleared;

        private ComputationOperation(@Nonnull PreflightKey key,
                                     @Nonnull AuthorityKey authority,
                                     long generation) {
            this.key = key;
            this.authority = authority;
            this.generation = generation;
        }
    }

    private record CachedPreflight(@Nonnull PathPreflightStatus status,
                                    @Nonnull String reason,
                                    long expiresAtMs,
                                    @Nullable ComputationOperation operation) {
    }

    @Nullable
    private PathPreflightResult resolveRecentReady(@Nonnull PreflightKey key, long nowMs) {
        RecentReadyKey recentKey = RecentReadyKey.from(key);
        RecentReadyPreflight recent = recentReadyTargets.get(recentKey);
        if (recent == null) {
            return null;
        }
        if (nowMs >= recent.expiresAtMs()) {
            recentReadyTargets.remove(recentKey, recent);
            return null;
        }
        if (!NeedsResourcePreflightPolicy.canReuseRecentReady(recent.key(), key)) {
            return null;
        }
        return PathPreflightResult.ready("path_preflight_recent_ready_target");
    }

    private void cacheRecentReadyLocked(@Nonnull PreflightKey key, long nowMs) {
        recentReadyTargets.put(
                RecentReadyKey.from(key),
                new RecentReadyPreflight(key, nowMs + NeedsResourcePreflightPolicy.RECENT_READY_TTL_MS)
        );
        cleanupRecentReadyLocked(nowMs);
    }

    private void cleanupRecentReadyLocked(long nowMs) {
        if (recentReadyTargets.size() < PRECHECK_CACHE_MAX_ENTRIES) {
            return;
        }
        recentReadyTargets.entrySet().removeIf(entry -> {
            RecentReadyPreflight value = entry == null ? null : entry.getValue();
            return entry == null || entry.getKey() == null || value == null || nowMs >= value.expiresAtMs();
        });
        int excess = recentReadyTargets.size() - PRECHECK_CACHE_MAX_ENTRIES;
        if (excess <= 0) {
            return;
        }
        for (RecentReadyKey key : recentReadyTargets.keySet()) {
            if (excess <= 0) {
                return;
            }
            if (recentReadyTargets.remove(key) != null) {
                excess--;
            }
        }
    }

    private record RecentReadyKey(@Nonnull UUID npcUuid,
                                  @Nonnull String worldName,
                                  @Nonnull String resourceType,
                                  @Nonnull String motionControllerType,
                                  int targetX,
                                  int targetY,
                                  int targetZ,
                                  int stopDistanceKey) {
        @Nonnull
        static RecentReadyKey from(@Nonnull PreflightKey key) {
            return new RecentReadyKey(
                    key.npcUuid(),
                    key.worldName(),
                    key.resourceType(),
                    key.motionControllerType(),
                    key.targetX(),
                    key.targetY(),
                    key.targetZ(),
                    key.stopDistanceKey()
            );
        }
    }

    private record RecentReadyPreflight(@Nonnull PreflightKey key, long expiresAtMs) {
    }

    private static final class HytalePathComputation implements PathComputation {
        private final Ref<EntityStore> ref;
        private final ComponentAccessor<EntityStore> componentAccessor;
        private final MotionController motionController;
        private final AStarWithTarget aStar;
        private final Vector3d start;
        private final Vector3d target;
        private final NeedsSeekPathEvaluator evaluator;
        private final ProbeMoveData probeMoveData;
        private final AStarNodePoolProviderSimple nodePoolProvider;
        private boolean started;

        private HytalePathComputation(@Nonnull Ref<EntityStore> ref,
                                      @Nonnull Store<EntityStore> store,
                                      @Nonnull MotionController motionController,
                                      @Nonnull AStarWithTarget aStar,
                                      @Nonnull Vector3d start,
                                      @Nonnull Vector3d target,
                                      @Nonnull NeedsSeekPathEvaluator evaluator,
                                      @Nonnull ProbeMoveData probeMoveData,
                                      @Nonnull AStarNodePoolProviderSimple nodePoolProvider) {
            this.ref = ref;
            this.componentAccessor = store;
            this.motionController = motionController;
            this.aStar = aStar;
            this.start = start;
            this.target = target;
            this.evaluator = evaluator;
            this.probeMoveData = probeMoveData;
            this.nodePoolProvider = nodePoolProvider;
        }

        @Nonnull
        @Override
        public PathPreflightStatus compute(int maxNodes) {
            if (maxNodes <= 0) {
                return PathPreflightStatus.COMPUTING;
            }
            AStarBase.Progress progress;
            if (!started) {
                progress = aStar.initComputePath(
                        ref,
                        start,
                        target,
                        evaluator,
                        motionController,
                        probeMoveData,
                        nodePoolProvider,
                        componentAccessor
                );
                started = true;
                if (progress != AStarBase.Progress.COMPUTING) {
                    return translateProgress(progress);
                }
            }
            progress = aStar.computePath(ref, motionController, probeMoveData, maxNodes, componentAccessor);
            return translateProgress(progress);
        }

        @Override
        public void clear() {
            aStar.clearPath();
        }

        @Nonnull
        private static PathPreflightStatus translateProgress(@Nonnull AStarBase.Progress progress) {
            if (progress == AStarBase.Progress.ACCOMPLISHED) {
                return PathPreflightStatus.READY;
            }
            if (progress == AStarBase.Progress.COMPUTING) {
                return PathPreflightStatus.COMPUTING;
            }
            return PathPreflightStatus.NO_PATH;
        }
    }

}
