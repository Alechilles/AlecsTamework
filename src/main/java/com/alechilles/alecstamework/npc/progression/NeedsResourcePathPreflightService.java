package com.alechilles.alecstamework.npc.progression;

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
    static final long NO_PATH_TTL_MS = 30_000L;
    static final long COMPUTING_TTL_MS = 5_000L;
    private static final int MAX_GLOBAL_NODES_PER_50MS = 512;
    private static final long GLOBAL_BUDGET_WINDOW_MS = 50L;
    private static final double DEFAULT_STOP_DISTANCE = 2.0;
    private static final double SAME_POSITION_DISTANCE_SQ = 0.25;
    private static final double EPSILON = 0.000001;
    private static final AtomicLong budgetWindowMs = new AtomicLong();
    private static final AtomicInteger budgetUsedNodes = new AtomicInteger();

    private final ConcurrentHashMap<PreflightKey, CachedPreflight> cache = new ConcurrentHashMap<>();

    @Nonnull
    public PathPreflightResult preflight(@Nonnull Ref<EntityStore> ref,
                                         @Nonnull Role role,
                                         @Nonnull Store<EntityStore> store,
                                         @Nonnull UUID npcUuid,
                                         @Nonnull String resourceType,
                                         @Nullable Vector3d target,
                                         long nowMs) {
        if (target == null || !isFinite(target)) {
            return PathPreflightResult.unavailable("path_preflight_target_invalid");
        }
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
                target
        );
        if (key == null) {
            return PathPreflightResult.unavailable("path_preflight_key_unavailable");
        }
        if (isSamePosition(start, target, motionController)) {
            cacheTerminalResult(key, PathPreflightStatus.READY, "path_preflight_already_at_target", nowMs);
            return PathPreflightResult.ready("path_preflight_already_at_target");
        }
        return preflight(
                key,
                () -> createPathComputation(
                        ref,
                        store,
                        motionController,
                        start,
                        target,
                        boundingBox.getBoundingBox(),
                        nodePoolProvider
                ),
                nowMs
        );
    }

    @Nonnull
    PathPreflightResult preflight(@Nonnull PreflightKey key,
                                  @Nonnull PathComputationFactory computationFactory,
                                  long nowMs) {
        CachedPreflight cached = cache.get(key);
        if (cached != null && nowMs < cached.expiresAtMs()) {
            if (cached.status() == PathPreflightStatus.READY) {
                return PathPreflightResult.ready(cached.reason());
            }
            if (cached.status() == PathPreflightStatus.NO_PATH) {
                return PathPreflightResult.noPath(cached.reason());
            }
        } else if (cached != null) {
            removeEntry(key, cached);
            cached = null;
        }

        PathComputation computation = cached != null ? cached.computation() : null;
        if (computation == null) {
            computation = computationFactory.create();
            if (computation == null) {
                return PathPreflightResult.unavailable("path_preflight_create_failed");
            }
        }

        int budget = claimGlobalBudget(nowMs, MAX_NODES_PER_SENSOR_PASS);
        if (budget <= 0) {
            cacheComputing(key, computation, "path_preflight_budget_deferred", nowMs);
            return PathPreflightResult.computing("path_preflight_budget_deferred");
        }

        PathPreflightStatus status;
        try {
            status = computation.compute(budget);
        } catch (RuntimeException ignored) {
            clearComputation(computation);
            cacheTerminalResult(key, PathPreflightStatus.NO_PATH, "path_preflight_exception", nowMs);
            return PathPreflightResult.noPath("path_preflight_exception");
        }

        if (status == PathPreflightStatus.READY) {
            clearComputation(computation);
            cacheTerminalResult(key, PathPreflightStatus.READY, "path_preflight_ready", nowMs);
            return PathPreflightResult.ready("path_preflight_ready");
        }
        if (status == PathPreflightStatus.NO_PATH) {
            clearComputation(computation);
            cacheTerminalResult(key, PathPreflightStatus.NO_PATH, "path_preflight_no_path", nowMs);
            return PathPreflightResult.noPath("path_preflight_no_path");
        }
        cacheComputing(key, computation, "path_preflight_computing", nowMs);
        return PathPreflightResult.computing("path_preflight_computing");
    }

    void clearForTests() {
        for (CachedPreflight value : cache.values()) {
            clearComputation(value.computation());
        }
        cache.clear();
        budgetWindowMs.set(0L);
        budgetUsedNodes.set(0);
    }

    int cacheSizeForTests() {
        return cache.size();
    }

    private PathComputation createPathComputation(@Nonnull Ref<EntityStore> ref,
                                                  @Nonnull Store<EntityStore> store,
                                                  @Nonnull MotionController motionController,
                                                  @Nonnull Vector3d start,
                                                  @Nonnull Vector3d target,
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
                new NeedsSeekPathEvaluator(target, selfBoundingBox, DEFAULT_STOP_DISTANCE),
                probeMoveData,
                nodePoolProvider
        );
    }

    private void cacheComputing(@Nonnull PreflightKey key,
                                @Nonnull PathComputation computation,
                                @Nonnull String reason,
                                long nowMs) {
        cache.put(key, new CachedPreflight(
                PathPreflightStatus.COMPUTING,
                reason,
                nowMs + COMPUTING_TTL_MS,
                computation
        ));
        cleanupCache(nowMs);
    }

    private void cacheTerminalResult(@Nonnull PreflightKey key,
                                     @Nonnull PathPreflightStatus status,
                                     @Nonnull String reason,
                                     long nowMs) {
        cache.put(key, new CachedPreflight(status, reason, nowMs + terminalTtlMs(status), null));
        cleanupCache(nowMs);
    }

    private static long terminalTtlMs(@Nonnull PathPreflightStatus status) {
        return status == PathPreflightStatus.READY ? READY_TTL_MS : NO_PATH_TTL_MS;
    }

    private void cleanupCache(long nowMs) {
        if (cache.size() < PRECHECK_CACHE_MAX_ENTRIES) {
            return;
        }
        cache.entrySet().removeIf(entry -> {
            CachedPreflight value = entry == null ? null : entry.getValue();
            boolean remove = entry == null || entry.getKey() == null || value == null || nowMs >= value.expiresAtMs();
            if (remove && value != null) {
                clearComputation(value.computation());
            }
            return remove;
        });
        int excess = cache.size() - PRECHECK_CACHE_MAX_ENTRIES;
        if (excess <= 0) {
            return;
        }
        for (PreflightKey key : cache.keySet()) {
            if (excess <= 0) {
                return;
            }
            CachedPreflight removed = cache.remove(key);
            if (removed != null) {
                clearComputation(removed.computation());
                excess--;
            }
        }
    }

    private void removeEntry(@Nonnull PreflightKey key, @Nonnull CachedPreflight value) {
        if (cache.remove(key, value)) {
            clearComputation(value.computation());
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

    private static boolean isSamePosition(@Nonnull Vector3d start,
                                          @Nonnull Vector3d target,
                                          @Nonnull MotionController motionController) {
        return motionController.waypointDistanceSquared(start, target) <= SAME_POSITION_DISTANCE_SQ + EPSILON;
    }

    private static boolean isFinite(@Nonnull Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
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
                        int targetZ) {
        @Nullable
        static PreflightKey from(@Nonnull UUID npcUuid,
                                 @Nullable String worldName,
                                 @Nonnull String resourceType,
                                 @Nullable String motionControllerType,
                                 @Nonnull Vector3d start,
                                 @Nonnull Vector3d target) {
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
                    (int) Math.floor(target.z)
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

    private record CachedPreflight(@Nonnull PathPreflightStatus status,
                                   @Nonnull String reason,
                                   long expiresAtMs,
                                   @Nullable PathComputation computation) {
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
