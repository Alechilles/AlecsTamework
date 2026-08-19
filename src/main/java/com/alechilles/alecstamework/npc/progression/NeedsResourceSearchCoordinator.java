package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.performance.RuntimePressureDomain;
import com.alechilles.alecstamework.performance.RuntimePressureLevel;
import com.alechilles.alecstamework.performance.TameworkRuntimePressureService;
import com.alechilles.alecstamework.util.StoreScopedState;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Coordinates bounded, deduplicated needs-resource searches for each world store.
 *
 * <p>The coordinator object is a process-wide facade. All mutable queues and
 * caches live in state keyed by the current store, so one world's cold-search
 * burst cannot consume another world's pending work.
 */
public final class NeedsResourceSearchCoordinator {
    public static final int MAX_PENDING_REQUESTS = 8_192;
    public static final String RESOURCE_KIND_WATER = "water";
    public static final String RESOURCE_KIND_FOOD_CONTAINER = "food_container";

    private static final long WARNING_THROTTLE_MS = 5_000L;
    private static final NeedsResourceSearchCoordinator INSTANCE =
            new NeedsResourceSearchCoordinator();
    private static final Logger LOGGER =
            Logger.getLogger(NeedsResourceSearchCoordinator.class.getName());

    private final NeedsResourceSearchAdmissionPolicy admissionPolicy =
            new NeedsResourceSearchAdmissionPolicy();
    private final TameworkRuntimePressureService pressureService =
            TameworkRuntimePressureService.getInstance();
    private final StoreScopedState<WorldState> statesByStore =
            new StoreScopedState<>(WorldState::new);

    private NeedsResourceSearchCoordinator() {
    }

    /**
     * Returns the shared facade used by sensors and the world-thread worker.
     */
    @Nonnull
    public static NeedsResourceSearchCoordinator getInstance() {
        return INSTANCE;
    }

    /**
     * Looks up a shared result or adds one cold request to the store queue.
     *
     * <p>A repeated request adds only a new UUID waiter. A full queue defers a
     * new key without changing queue state; both cases are normal pressure
     * behavior and do not produce a warning.
     */
    @Nonnull
    public Lookup lookupOrEnqueue(@Nonnull Store<EntityStore> store,
                                  @Nonnull UUID npcId,
                                  @Nonnull Request request,
                                  long nowMs) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(npcId, "npcId");
        Objects.requireNonNull(request, "request");
        WorldState state = statesByStore.get(store);
        NeedsResourceCandidates.Snapshot snapshot = state.areaCache.getSnapshot(
                request.areaKey(),
                nowMs
        );
        RequestKey key = new RequestKey(request);
        if (snapshot != null) {
            state.pending.remove(key);
            return lookupFor(snapshot);
        }

        PendingRequest pending = state.pending.get(key);
        if (pending != null) {
            pending.addWaiter(npcId);
            return Lookup.deferred();
        }
        if (state.pending.size() >= MAX_PENDING_REQUESTS) {
            return Lookup.deferred();
        }

        state.pending.put(key, new PendingRequest(request, npcId));
        return Lookup.deferred();
    }

    /**
     * Executes at most one oldest pending search when the current pressure
     * level admits work for this world tick.
     *
     * @return one when a search was attempted, otherwise zero
     */
    public int processOne(@Nonnull Store<EntityStore> store,
                          long worldTick,
                          long nowMs,
                          @Nonnull SearchExecutor executor) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(executor, "executor");
        return processState(store, statesByStore.get(store), worldTick, nowMs, executor);
    }

    private int processState(@Nonnull Store<EntityStore> store,
                             @Nonnull WorldState state,
                             long worldTick,
                             long nowMs,
                             @Nonnull SearchExecutor executor) {
        if (state.pending.isEmpty()) {
            return 0;
        }
        if (state.attemptedTickInitialized && state.lastAttemptedWorldTick == worldTick) {
            return 0;
        }
        RuntimePressureLevel pressure = pressureService.level(
                RuntimePressureDomain.NEEDS_RESOURCE_SEARCH,
                nowMs
        );
        if (!admissionPolicy.maySearch(pressure, worldTick)) {
            return 0;
        }
        state.attemptedTickInitialized = true;
        state.lastAttemptedWorldTick = worldTick;

        Map.Entry<RequestKey, PendingRequest> entry = state.pending.entrySet().iterator().next();
        state.pending.remove(entry.getKey());
        PendingRequest pending = entry.getValue();
        List<UUID> waiters = List.copyOf(pending.waiters);
        long startedNs = System.nanoTime();
        NeedsResourceCandidates.Snapshot snapshot;
        try {
            snapshot = executor.search(store, pending.request, waiters);
        } catch (RuntimeException exception) {
            recordSearchWork(startedNs, nowMs);
            recoverFromSearchFailure(store, state, pending, waiters, nowMs, exception);
            return 1;
        }
        recordSearchWork(startedNs, nowMs);
        if (snapshot == null) {
            return 1;
        }
        state.areaCache.put(
                pending.request.areaKey(),
                NeedsResourceSearchCachePolicy.scaleSharedSnapshotTtl(snapshot, nowMs),
                nowMs
        );
        return 1;
    }

    /** Advances the store-owned tick and attempts one admitted search. */
    public int processNext(@Nonnull Store<EntityStore> store,
                           long nowMs,
                           @Nonnull SearchExecutor executor) {
        Objects.requireNonNull(store, "store");
        WorldState state = statesByStore.get(store);
        Objects.requireNonNull(executor, "executor");
        return processState(store, state, ++state.nextWorldTick, nowMs, executor);
    }

    /**
     * Removes all queued and cached state owned by one world store.
     *
     * <p>The world lifecycle owner should call this when the store is removed.
     */
    public void clear(@Nonnull Store<EntityStore> store) {
        statesByStore.remove(Objects.requireNonNull(store, "store"));
    }

    /**
     * Alias for lifecycle callers that describe world teardown as removal.
     */
    public void remove(@Nonnull Store<EntityStore> store) {
        clear(store);
    }

    int pendingCountForTests(@Nonnull Store<EntityStore> store) {
        return statesByStore.get(Objects.requireNonNull(store, "store")).pending.size();
    }

    void clearForTests(@Nonnull Store<EntityStore> store) {
        clear(store);
    }

    private static Lookup lookupFor(@Nonnull NeedsResourceCandidates.Snapshot snapshot) {
        return snapshot.hasCandidates() ? Lookup.hit(snapshot) : Lookup.miss(snapshot);
    }

    private void recoverFromSearchFailure(@Nonnull Store<EntityStore> store,
                                          @Nonnull WorldState state,
                                          @Nonnull PendingRequest pending,
                                          @Nonnull List<UUID> waiters,
                                          long nowMs,
                                          @Nonnull RuntimeException exception) {
        UUID sourceNpc = waiters.isEmpty() ? new UUID(0L, 0L) : waiters.get(0);
        long ttlMs = admissionPolicy.deferredTtlMs(sourceNpc);
        NeedsResourceCandidates.Snapshot shortMiss = new NeedsResourceCandidates.Snapshot(
                List.of(),
                false,
                false,
                ttlMs
        );
        state.areaCache.put(pending.request.areaKey(), shortMiss, nowMs);
        if (isWarningDue(state.lastFailureWarningMs, nowMs)) {
            state.lastFailureWarningMs = nowMs;
            LOGGER.log(Level.WARNING,
                    "[tw-needs-resource] search failed; store="
                            + Integer.toHexString(System.identityHashCode(store))
                            + ", request=" + pending.request
                            + ", error=" + exception.getClass().getSimpleName());
        }
    }

    private void recordSearchWork(long startedNs, long nowMs) {
        long elapsedNs = Math.max(0L, System.nanoTime() - startedNs);
        pressureService.recordWork(
                RuntimePressureDomain.NEEDS_RESOURCE_SEARCH,
                elapsedNs,
                nowMs
        );
    }

    private static boolean isWarningDue(long lastWarningMs, long nowMs) {
        if (lastWarningMs == Long.MIN_VALUE) {
            return true;
        }
        return nowMs >= lastWarningMs && nowMs - lastWarningMs >= WARNING_THROTTLE_MS;
    }

    private static final class WorldState {
        private final NeedsResourceAreaSearchCache areaCache =
                new NeedsResourceAreaSearchCache(MAX_PENDING_REQUESTS);
        private final LinkedHashMap<RequestKey, PendingRequest> pending = new LinkedHashMap<>();
        private long lastFailureWarningMs = Long.MIN_VALUE;
        private long nextWorldTick;
        private boolean attemptedTickInitialized;
        private long lastAttemptedWorldTick;
    }

    private static final class PendingRequest {
        private final Request request;
        private final LinkedHashSet<UUID> waiters = new LinkedHashSet<>();

        private PendingRequest(@Nonnull Request request, @Nonnull UUID firstWaiter) {
            this.request = request;
            addWaiter(firstWaiter);
        }

        private void addWaiter(@Nonnull UUID waiter) {
            waiters.add(waiter);
        }
    }

    private record RequestKey(@Nonnull Request request) {
    }

    /**
     * Immutable cold-search input. It contains only area identity and scalar
     * values; no ECS references, world objects, stores, or mutable vectors.
     */
    public static final class Request {
        @Nonnull
        private final String resourceKind;
        @Nonnull
        private final NeedsResourceAreaSearchCache.AreaKey areaKey;
        private final double radius;
        private final int verticalRadius;
        private final double consumeRadius;
        @Nonnull
        private final List<String> itemIds;

        private Request(@Nonnull String resourceKind,
                        @Nonnull NeedsResourceAreaSearchCache.AreaKey areaKey,
                        double radius,
                        int verticalRadius,
                        double consumeRadius) {
            this.resourceKind = normalizeResourceKind(resourceKind);
            this.areaKey = Objects.requireNonNull(areaKey, "areaKey");
            this.radius = requirePositiveFinite(
                    NeedsResourceSearchCachePolicy.boundedSearchRadius(radius),
                    "radius"
            );
            this.verticalRadius = NeedsResourceSearchCachePolicy.boundedVerticalScanRadius(verticalRadius);
            this.consumeRadius = requireNonNegativeFinite(
                    NeedsResourceSearchCachePolicy.boundedConsumeRadius(consumeRadius),
                    "consumeRadius"
            );
            this.itemIds = areaKey.normalizedItemIds();
        }

        /**
         * Builds a request from immutable scalar origin data for callers that
         * cannot access the package-private area-key type.
         */
        @Nonnull
        public static Request forArea(@Nonnull String resourceKind,
                                      @Nonnull String worldName,
                                      double originX,
                                      double originY,
                                      double originZ,
                                      double radius,
                                      int verticalRadius,
                                      double consumeRadius,
                                      @Nonnull List<String> itemIds) {
            return createForArea(
                    resourceKind,
                    worldName,
                    originX,
                    originY,
                    originZ,
                    radius,
                    verticalRadius,
                    consumeRadius,
                    itemIds
            );
        }

        /*
         * The normalized values are calculated before AreaKey.from so a
         * malformed finite request cannot widen the integer traversal.
         */
        private static Request createForArea(@Nonnull String resourceKind,
                                             @Nonnull String worldName,
                                             double originX,
                                             double originY,
                                             double originZ,
                                             double radius,
                                             int verticalRadius,
                                             double consumeRadius,
                                             @Nonnull List<String> itemIds) {
            double normalizedRadius = NeedsResourceSearchCachePolicy.boundedSearchRadius(radius);
            int normalizedVerticalRadius = NeedsResourceSearchCachePolicy.boundedVerticalScanRadius(verticalRadius);
            double normalizedConsumeRadius = NeedsResourceSearchCachePolicy.boundedConsumeRadius(consumeRadius);
            if (!Double.isFinite(normalizedRadius)
                    || normalizedRadius <= 0.0
                    || !Double.isFinite(normalizedConsumeRadius)
                    || normalizedConsumeRadius < 0.0
                    || !NeedsResourceSearchCachePolicy.hasSafeOrigin(
                    originX,
                    originY,
                    originZ,
                    Math.max(1, (int) Math.ceil(normalizedRadius)),
                    normalizedVerticalRadius
            )) {
                throw new IllegalArgumentException("request bounds are invalid");
            }
            NeedsResourceAreaSearchCache.AreaKey areaKey = NeedsResourceAreaSearchCache.AreaKey.from(
                    worldName,
                    resourceKind,
                    originX,
                    originY,
                    originZ,
                    normalizedRadius,
                    normalizedVerticalRadius,
                    normalizedConsumeRadius,
                    itemIds
            );
            if (areaKey == null) {
                throw new IllegalArgumentException("area key cannot be built from the supplied origin");
            }
            return new Request(
                    resourceKind,
                    areaKey,
                    normalizedRadius,
                    normalizedVerticalRadius,
                    normalizedConsumeRadius
            );
        }

        @Nonnull
        public String resourceKind() {
            return resourceKind;
        }

        @Nonnull
        NeedsResourceAreaSearchCache.AreaKey areaKey() {
            return areaKey;
        }

        public double radius() {
            return radius;
        }

        public int verticalRadius() {
            return verticalRadius;
        }

        public int verticalScanRadius() {
            return verticalRadius;
        }

        public double consumeRadius() {
            return consumeRadius;
        }

        public double searchRadius() {
            return radius;
        }

        /** Returns whether a current waiter position remains in the queued area cell. */
        public boolean isInQueuedArea(double positionX, double positionY, double positionZ) {
            return areaKey.containsPosition(positionX, positionY, positionZ);
        }

        @Nonnull
        public List<String> itemIds() {
            return itemIds;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Request request)) {
                return false;
            }
            return Double.compare(radius, request.radius) == 0
                    && verticalRadius == request.verticalRadius
                    && Double.compare(consumeRadius, request.consumeRadius) == 0
                    && resourceKind.equals(request.resourceKind)
                    && areaKey.equals(request.areaKey)
                    && itemIds.equals(request.itemIds);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    resourceKind,
                    areaKey,
                    radius,
                    verticalRadius,
                    consumeRadius,
                    itemIds
            );
        }

        @Override
        public String toString() {
            return "Request[resourceKind=" + resourceKind
                    + ", areaKey=" + areaKey
                    + ", radius=" + radius
                    + ", verticalRadius=" + verticalRadius
                    + ", consumeRadius=" + consumeRadius
                    + ", itemIds=" + itemIds
                    + "]";
        }

        @Nonnull
        private static String normalizeResourceKind(@Nonnull String value) {
            String normalized = Objects.requireNonNull(value, "resourceKind")
                    .trim()
                    .toLowerCase(Locale.ROOT);
            if (!RESOURCE_KIND_WATER.equals(normalized)
                    && !RESOURCE_KIND_FOOD_CONTAINER.equals(normalized)) {
                throw new IllegalArgumentException("unsupported resource kind: " + value);
            }
            return normalized;
        }

        private static double requirePositiveFinite(double value, @Nonnull String name) {
            if (!Double.isFinite(value) || value <= 0.0) {
                throw new IllegalArgumentException(name + " must be finite and positive");
            }
            return value;
        }

        private static double requireNonNegativeFinite(double value, @Nonnull String name) {
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException(name + " must be finite and non-negative");
            }
            return value;
        }

    }

    /**
     * Result returned to a sensor after a cache lookup or queue admission.
     */
    public record Lookup(@Nonnull Status status,
                         @Nullable NeedsResourceCandidates.Snapshot snapshot) {
        public Lookup {
            Objects.requireNonNull(status, "status");
            if (status == Status.DEFERRED && snapshot != null) {
                throw new IllegalArgumentException("deferred lookups cannot contain a snapshot");
            }
        }

        @Nonnull
        static Lookup hit(@Nonnull NeedsResourceCandidates.Snapshot snapshot) {
            return new Lookup(Status.HIT, snapshot);
        }

        @Nonnull
        static Lookup miss(@Nonnull NeedsResourceCandidates.Snapshot snapshot) {
            return new Lookup(Status.MISS, snapshot);
        }

        @Nonnull
        static Lookup deferred() {
            return new Lookup(Status.DEFERRED, null);
        }

        @Nonnull
        public Optional<NeedsResourceCandidates.Snapshot> snapshotOptional() {
            return Optional.ofNullable(snapshot);
        }

        public enum Status {
            HIT,
            MISS,
            DEFERRED
        }
    }

    /**
     * Runs one synchronous cold search on the current world thread.
     *
     * <p>A null result intentionally discards a request whose waiter no longer
     * exists. The coordinator records the attempt but does not cache a miss.
     */
    @FunctionalInterface
    public interface SearchExecutor {
        @Nullable
        NeedsResourceCandidates.Snapshot search(@Nonnull Store<EntityStore> store,
                                                @Nonnull Request request,
                                                @Nonnull List<UUID> waiterIds);
    }
}
