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
import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;
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

    private final NeedsResourcePathPreflightState state = new NeedsResourcePathPreflightState();

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
        NeedsResourceHotPathDiagnostics.recordPreflightRequest();
        NeedsResourceHotPathDiagnostics.recordPreflightLeaseMiss();
        synchronized (state.lock()) {
            NeedsResourcePathPreflightState.ComputationOperation operation = state.registerOperationLocked(key);
            if (!state.isCurrentOperationLocked(operation)) {
                state.clearOperationLocked(operation);
                return PathPreflightResult.unavailable("path_preflight_invalidated");
            }
            state.cacheTerminalResultLocked(
                    key,
                    PathPreflightStatus.READY,
                    reason,
                    nowMs + terminalTtlMs(PathPreflightStatus.READY, nowMs)
            );
            state.removeOperationLocked(operation);
            return PathPreflightResult.ready(reason);
        }
    }

    @Nonnull
    PathPreflightResult preflight(@Nonnull PreflightKey key,
                                  @Nonnull PathComputationFactory computationFactory,
                                  long nowMs) {
        NeedsResourceHotPathDiagnostics.recordPreflightRequest();
        NeedsResourcePathPreflightState.ComputationOperation operation;
        boolean createComputation = false;
        synchronized (state.lock()) {
            NeedsResourcePathPreflightState.RecentReadyPreflight recentReady =
                    state.resolveRecentReadyLocked(key, nowMs);
            if (recentReady != null
                    && NeedsResourcePreflightPolicy.canReuseRecentReady(recentReady.key(), key)) {
                NeedsResourceHotPathDiagnostics.recordPreflightLeaseHit();
                return PathPreflightResult.ready("path_preflight_recent_ready_target");
            }
            NeedsResourcePathPreflightState.CachedPreflight cached = state.cacheEntryLocked(key);
            if (cached != null && nowMs < cached.expiresAtMs()) {
                if (cached.status() == PathPreflightStatus.READY) {
                    NeedsResourceHotPathDiagnostics.recordPreflightLeaseHit();
                    return PathPreflightResult.ready(cached.reason());
                }
                if (cached.status() == PathPreflightStatus.NO_PATH) {
                    NeedsResourceHotPathDiagnostics.recordPreflightLeaseMiss();
                    NeedsResourceHotPathDiagnostics.recordPreflightNoPathResult();
                    return PathPreflightResult.noPath(cached.reason());
                }
                operation = cached.operation();
                if (state.isCurrentOperationLocked(operation)) {
                    if (operation.computing) {
                        NeedsResourceHotPathDiagnostics.recordPreflightLeaseMiss();
                        return PathPreflightResult.computing("path_preflight_computing");
                    }
                } else {
                    state.removeCacheEntryLocked(key, cached);
                    operation = null;
                }
            } else {
                if (cached != null) {
                    state.removeCacheEntryLocked(key, cached);
                }
                operation = null;
            }
            if (operation == null) {
                operation = state.findActiveOperationLocked(key);
            }
            if (operation == null) {
                operation = state.registerOperationLocked(key);
                createComputation = true;
            } else if (operation.computation == null) {
                NeedsResourceHotPathDiagnostics.recordPreflightLeaseMiss();
                return PathPreflightResult.computing("path_preflight_computing");
            }
        }

        NeedsResourceHotPathDiagnostics.recordPreflightLeaseMiss();
        if (createComputation) {
            NeedsResourceHotPathDiagnostics.recordPreflightComputation();
            PathComputation computation;
            try {
                computation = computationFactory.create();
            } catch (RuntimeException failure) {
                synchronized (state.lock()) {
                    state.cancelOperationLocked(operation);
                }
                throw failure;
            }
            synchronized (state.lock()) {
                operation.computation = computation;
                if (!state.isCurrentOperationLocked(operation)) {
                    state.clearOperationLocked(operation);
                    return PathPreflightResult.unavailable("path_preflight_invalidated");
                }
                if (computation == null) {
                    state.clearOperationLocked(operation);
                    return PathPreflightResult.unavailable("path_preflight_create_failed");
                }
            }
        }
        return runOperation(operation, nowMs);
    }

    @Nonnull
    private PathPreflightResult runOperation(
            @Nonnull NeedsResourcePathPreflightState.ComputationOperation operation,
            long nowMs) {
        int budget;
        synchronized (state.lock()) {
            if (!state.isCurrentOperationLocked(operation) || operation.computation == null) {
                state.clearOperationLocked(operation);
                return PathPreflightResult.unavailable("path_preflight_invalidated");
            }
            if (operation.computing) {
                return PathPreflightResult.computing("path_preflight_computing");
            }
            budget = claimGlobalBudget(nowMs, MAX_NODES_PER_SENSOR_PASS);
            if (budget <= 0) {
                NeedsResourceHotPathDiagnostics.recordPreflightBudgetDeferral();
                state.cacheComputingLocked(
                        operation.key,
                        operation,
                        "path_preflight_budget_deferred",
                        nowMs
                );
                return state.isCurrentOperationLocked(operation)
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

        synchronized (state.lock()) {
            operation.computing = false;
            if (!state.isCurrentOperationLocked(operation)) {
                state.clearOperationLocked(operation);
                return PathPreflightResult.unavailable("path_preflight_invalidated");
            }
            if (failed) {
                NeedsResourceHotPathDiagnostics.recordPreflightNoPathResult();
                state.clearOperationLocked(operation);
                state.cacheTerminalResultLocked(
                        operation.key,
                        PathPreflightStatus.NO_PATH,
                        "path_preflight_exception",
                        nowMs + terminalTtlMs(PathPreflightStatus.NO_PATH, nowMs)
                );
                return PathPreflightResult.noPath("path_preflight_exception");
            }
            if (status == PathPreflightStatus.READY) {
                state.clearOperationLocked(operation);
                state.cacheTerminalResultLocked(
                        operation.key,
                        PathPreflightStatus.READY,
                        "path_preflight_ready",
                        nowMs + terminalTtlMs(PathPreflightStatus.READY, nowMs)
                );
                state.cacheRecentReadyLocked(
                        operation.key,
                        nowMs + NeedsResourcePreflightPolicy.RECENT_READY_TTL_MS
                );
                return PathPreflightResult.ready("path_preflight_ready");
            }
            if (status == PathPreflightStatus.NO_PATH) {
                NeedsResourceHotPathDiagnostics.recordPreflightNoPathResult();
                state.clearOperationLocked(operation);
                state.cacheTerminalResultLocked(
                        operation.key,
                        PathPreflightStatus.NO_PATH,
                        "path_preflight_no_path",
                        nowMs + terminalTtlMs(PathPreflightStatus.NO_PATH, nowMs)
                );
                return PathPreflightResult.noPath("path_preflight_no_path");
            }
            state.cacheComputingLocked(operation.key, operation, "path_preflight_computing", nowMs);
            return state.isCurrentOperationLocked(operation)
                    ? PathPreflightResult.computing("path_preflight_computing")
                    : PathPreflightResult.unavailable("path_preflight_invalidated");
        }
    }

    void clearForTests() {
        synchronized (state.lock()) {
            state.clearForTestsLocked();
        }
        budgetWindowMs.set(0L);
        budgetUsedNodes.set(0);
    }

    int cacheSizeForTests() {
        synchronized (state.lock()) {
            return state.cacheSizeLocked();
        }
    }

    int recentReadySizeForTests() {
        synchronized (state.lock()) {
            return state.recentReadySizeLocked();
        }
    }

    int indexedCacheKeyCountForTests() {
        synchronized (state.lock()) {
            return state.indexedCacheKeyCountLocked();
        }
    }

    int indexedRecentReadyKeyCountForTests() {
        synchronized (state.lock()) {
            return state.indexedRecentReadyKeyCountLocked();
        }
    }

    int authorityIndexSizeForTests() {
        synchronized (state.lock()) {
            return state.authorityIndexSizeLocked();
        }
    }

    long cacheAdmissionWorkForTests() {
        synchronized (state.lock()) {
            return state.cacheAdmissionWorkLocked();
        }
    }

    int targetInvalidationBucketVisitsForTests() {
        synchronized (state.lock()) {
            return state.targetInvalidationBucketVisitsLocked();
        }
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
        NeedsResourceHotPathDiagnostics.recordPreflightInvalidation();
        synchronized (state.lock()) {
            state.invalidateTargetLocked(
                    npcUuid,
                    normalizedWorld,
                    normalizedResource,
                    targetX,
                    targetY,
                    targetZ
            );
        }
    }

    /** Removes all cache and lease entries for one normalized world. */
    public void clearWorld(@Nullable String worldName) {
        String normalizedWorld = normalizeWorldNameForMatch(worldName);
        if (normalizedWorld == null) {
            return;
        }
        synchronized (state.lock()) {
            state.clearWorldLocked(normalizedWorld);
        }
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
