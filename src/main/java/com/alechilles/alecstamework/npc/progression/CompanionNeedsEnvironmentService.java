package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.items.FeedTroughContainerCompat;
import com.alechilles.alecstamework.items.FeedTroughWaterStateService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.modules.collision.WorldUtil;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Handles world-environment probes for needs progression (nearby water and nearby container food).
 */
public final class CompanionNeedsEnvironmentService {
    private static final int DEFAULT_CONTAINER_VERTICAL_SCAN_RADIUS = 2;
    private static final int DEFAULT_WATER_VERTICAL_SCAN_RADIUS = 1;
    private static final long SEARCH_CACHE_HIT_TTL_MS = 1_500L;
    private static final long SEARCH_CACHE_MISS_TTL_MS = 500L;
    private static final int SEARCH_CACHE_MAX_ENTRIES = 8192;
    private static final int[][] HORIZONTAL_NEIGHBOR_OFFSETS = {
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1}
    };
    private static final int[] STAND_HEIGHT_OFFSETS = {0, 1, -1};
    private static final double STAND_POSITION_Y_OFFSET = 0.05;
    private static final double SCORE_EPSILON = 0.000001;
    private static final ConcurrentHashMap<NeedsSearchCacheKey, CachedSearchResult> SEARCH_CACHE = new ConcurrentHashMap<>();

    enum ContainerConsumeStatus {
        SUCCESS,
        INVALID_CONTEXT,
        MAX_ITEMS_NON_POSITIVE,
        ALLOWED_FOODS_EMPTY,
        WORLD_CONTEXT_MISSING,
        INVALID_RADIUS,
        CHUNK_STORE_UNAVAILABLE,
        NO_CONTAINER_IN_RANGE,
        NO_ALLOWED_FOOD_IN_RANGE,
        REMOVE_TRANSACTION_FAILED,
        NO_ITEMS_CONSUMED
    }

    static final class ContainerConsumeResult {
        private final int consumedItems;
        @Nonnull
        private final Map<String, Integer> consumedItemCountsByItemId;
        private final int scannedContainers;
        private final int containersWithAllowedFood;
        private final int matchingStacksSeen;
        private final int removalAttempts;
        private final int removalFailures;
        private final int maxItems;
        private final double radius;
        private final int verticalScanRadius;
        private final int scanBlockX;
        private final int scanBlockY;
        private final int scanBlockZ;
        private final int npcBlockX;
        private final int npcBlockY;
        private final int npcBlockZ;
        private final boolean scanFromOverride;
        private final double nearestContainerDistance;
        private final double nearestAllowedContainerDistance;
        @Nonnull
        private final ContainerConsumeStatus status;

        ContainerConsumeResult(int consumedItems,
                               int scannedContainers,
                               int containersWithAllowedFood,
                               int matchingStacksSeen,
                               int removalAttempts,
                               int removalFailures,
                               int maxItems,
                               double radius,
                               int verticalScanRadius,
                               int scanBlockX,
                               int scanBlockY,
                               int scanBlockZ,
                               int npcBlockX,
                               int npcBlockY,
                               int npcBlockZ,
                               boolean scanFromOverride,
                               double nearestContainerDistance,
                               double nearestAllowedContainerDistance,
                               @Nonnull ContainerConsumeStatus status) {
            this(
                    consumedItems,
                    Map.of(),
                    scannedContainers,
                    containersWithAllowedFood,
                    matchingStacksSeen,
                    removalAttempts,
                    removalFailures,
                    maxItems,
                    radius,
                    verticalScanRadius,
                    scanBlockX,
                    scanBlockY,
                    scanBlockZ,
                    npcBlockX,
                    npcBlockY,
                    npcBlockZ,
                    scanFromOverride,
                    nearestContainerDistance,
                    nearestAllowedContainerDistance,
                    status
            );
        }

        ContainerConsumeResult(int consumedItems,
                               @Nullable Map<String, Integer> consumedItemCountsByItemId,
                               int scannedContainers,
                               int containersWithAllowedFood,
                               int matchingStacksSeen,
                               int removalAttempts,
                               int removalFailures,
                               int maxItems,
                               double radius,
                               int verticalScanRadius,
                               int scanBlockX,
                               int scanBlockY,
                               int scanBlockZ,
                               int npcBlockX,
                               int npcBlockY,
                               int npcBlockZ,
                               boolean scanFromOverride,
                               double nearestContainerDistance,
                               double nearestAllowedContainerDistance,
                               @Nonnull ContainerConsumeStatus status) {
            this.consumedItems = consumedItems;
            this.consumedItemCountsByItemId = consumedItemCountsByItemId == null || consumedItemCountsByItemId.isEmpty()
                    ? Map.of()
                    : Map.copyOf(consumedItemCountsByItemId);
            this.scannedContainers = scannedContainers;
            this.containersWithAllowedFood = containersWithAllowedFood;
            this.matchingStacksSeen = matchingStacksSeen;
            this.removalAttempts = removalAttempts;
            this.removalFailures = removalFailures;
            this.maxItems = maxItems;
            this.radius = radius;
            this.verticalScanRadius = verticalScanRadius;
            this.scanBlockX = scanBlockX;
            this.scanBlockY = scanBlockY;
            this.scanBlockZ = scanBlockZ;
            this.npcBlockX = npcBlockX;
            this.npcBlockY = npcBlockY;
            this.npcBlockZ = npcBlockZ;
            this.scanFromOverride = scanFromOverride;
            this.nearestContainerDistance = nearestContainerDistance;
            this.nearestAllowedContainerDistance = nearestAllowedContainerDistance;
            this.status = status;
        }

        int getConsumedItems() {
            return consumedItems;
        }

        @Nonnull
        Map<String, Integer> getConsumedItemCountsByItemId() {
            return consumedItemCountsByItemId;
        }

        @Nonnull
        ContainerConsumeStatus getStatus() {
            return status;
        }

        @Nonnull
        String toSummary() {
            return "status=" + status
                    + ",containers=" + scannedContainers
                    + ",allowedContainers=" + containersWithAllowedFood
                    + ",matchingStacks=" + matchingStacksSeen
                    + ",attempts=" + removalAttempts
                    + ",failures=" + removalFailures
                    + ",maxItems=" + maxItems
                    + ",radius=" + String.format(Locale.ROOT, "%.2f", radius)
                    + ",vScan=" + verticalScanRadius
                    + ",scanSource=" + (scanFromOverride ? "TARGET" : "NPC")
                    + ",scanBlock=[" + scanBlockX + "," + scanBlockY + "," + scanBlockZ + "]"
                    + ",npcBlock=[" + npcBlockX + "," + npcBlockY + "," + npcBlockZ + "]"
                    + ",nearestContainerDist=" + formatDistance(nearestContainerDistance)
                    + ",nearestAllowedContainerDist=" + formatDistance(nearestAllowedContainerDistance)
                    + ",consumedByItem=" + consumedItemCountsByItemId;
        }

        @Nonnull
        private static String formatDistance(double value) {
            if (!Double.isFinite(value)) {
                return "n/a";
            }
            return String.format(Locale.ROOT, "%.2f", value);
        }
    }

    private static final class SlotConsumeResult {
        private final int consumed;
        @Nonnull
        private final Map<String, Integer> consumedItemCountsByItemId;
        private final int matchingStacksSeen;
        private final int removalAttempts;
        private final int removalFailures;

        private SlotConsumeResult(int consumed, int matchingStacksSeen, int removalAttempts, int removalFailures) {
            this(consumed, Map.of(), matchingStacksSeen, removalAttempts, removalFailures);
        }

        private SlotConsumeResult(int consumed,
                                  @Nullable Map<String, Integer> consumedItemCountsByItemId,
                                  int matchingStacksSeen,
                                  int removalAttempts,
                                  int removalFailures) {
            this.consumed = consumed;
            this.consumedItemCountsByItemId = consumedItemCountsByItemId == null || consumedItemCountsByItemId.isEmpty()
                    ? Map.of()
                    : Map.copyOf(consumedItemCountsByItemId);
            this.matchingStacksSeen = matchingStacksSeen;
            this.removalAttempts = removalAttempts;
            this.removalFailures = removalFailures;
        }
    }

    public boolean isNearWater(@Nullable Ref<EntityStore> npcRef,
                               @Nullable Store<EntityStore> store,
                               @Nonnull TwNeedsConfig config) {
        return isNearWater(npcRef, store, config, null);
    }

    public boolean isNearWater(@Nullable Ref<EntityStore> npcRef,
                               @Nullable Store<EntityStore> store,
                               @Nonnull TwNeedsConfig config,
                               @Nullable Vector3d scanCenterOverride) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        World world = resolveWorld(store);
        if (transform == null || world == null || world.getChunkStore() == null) {
            return false;
        }
        TwNeedsConfig.PassiveRefillSettings passiveRefill = config.getPassiveRefill();
        double radius = passiveRefill.getWaterConsumeRadius();
        int verticalScanRadius = passiveRefill.getWaterVerticalScanRadius();
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return false;
        }
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunkStoreStore = chunkStore.getStore();
        if (chunkStoreStore == null) {
            return false;
        }
        Vector3d scanCenter = scanCenterOverride;
        if (scanCenter == null
                || !Double.isFinite(scanCenter.x)
                || !Double.isFinite(scanCenter.y)
                || !Double.isFinite(scanCenter.z)) {
            scanCenter = transform.getPosition();
        }
        int blockX = (int) Math.floor(scanCenter.x);
        int blockY = (int) Math.floor(scanCenter.y);
        int blockZ = (int) Math.floor(scanCenter.z);
        int searchRadius = Math.max(1, (int) Math.ceil(radius));
        double radiusSq = radius * radius;
        Map<Long, WorldChunk> chunkCache = new HashMap<>();
        for (int y = blockY - verticalScanRadius; y <= blockY + verticalScanRadius; y++) {
            for (int x = blockX - searchRadius; x <= blockX + searchRadius; x++) {
                for (int z = blockZ - searchRadius; z <= blockZ + searchRadius; z++) {
                    double dx = x - blockX;
                    double dz = z - blockZ;
                    if ((dx * dx) + (dz * dz) > radiusSq) {
                        continue;
                    }
                    WorldChunk worldChunk = resolveWorldChunk(chunkStore, chunkStoreStore, x, z, chunkCache);
                    if (worldChunk == null) {
                        continue;
                    }
                    if (isConsumableWaterSourceAt(worldChunk, chunkStoreStore, x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Nullable
    Vector3d findNearestWaterDrinkingPosition(@Nullable Ref<EntityStore> npcRef,
                                              @Nullable Store<EntityStore> store,
                                              @Nonnull TwNeedsConfig config) {
        if (config == null) {
            return null;
        }
        TwNeedsConfig.PassiveRefillSettings passiveRefill = config.getPassiveRefill();
        return findNearestWaterDrinkingPosition(
                npcRef,
                store,
                passiveRefill.getWaterSearchRadius(),
                passiveRefill.getWaterVerticalScanRadius()
        );
    }

    @Nullable
    public Vector3d findNearestWaterDrinkingPosition(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store,
                                                     double radius) {
        return findNearestWaterDrinkingPosition(
                npcRef,
                store,
                radius,
                DEFAULT_WATER_VERTICAL_SCAN_RADIUS
        );
    }

    @Nullable
    public Vector3d findNearestWaterDrinkingPosition(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store,
                                                     double radius,
                                                     int verticalScanRadius) {
        return findNearestWaterDrinkingPosition(npcRef, store, radius, verticalScanRadius, 1.0);
    }

    @Nullable
    public Vector3d findNearestWaterDrinkingPosition(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store,
                                                     double radius,
                                                     int verticalScanRadius,
                                                     double consumeRadius) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return null;
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        World world = resolveWorld(store);
        if (transform == null || world == null || world.getChunkStore() == null) {
            return null;
        }
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return null;
        }
        long nowMs = resolveCurrentTimeMs(store);
        NeedsSearchCacheKey cacheKey = buildSearchCacheKey(
                npcRef,
                store,
                ResourceSearchKind.WATER,
                transform.getPosition(),
                radius,
                verticalScanRadius,
                null
        );
        Vector3d cachedTarget = getCachedSearchTarget(cacheKey, nowMs);
        if (cachedTarget != CACHE_MISS) {
            return cachedTarget;
        }
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunkStoreStore = chunkStore.getStore();
        if (chunkStoreStore == null) {
            return null;
        }

        int blockX = (int) Math.floor(transform.getPosition().x);
        int blockY = (int) Math.floor(transform.getPosition().y);
        int blockZ = (int) Math.floor(transform.getPosition().z);
        int searchRadius = Math.max(1, (int) Math.ceil(radius));
        double radiusSq = radius * radius;
        int clampedVerticalRadius = Math.max(0, verticalScanRadius);
        Map<Long, WorldChunk> chunkCache = new HashMap<>();
        // Prefer the same edge-adjacent style target that food seek uses so the NPC visibly walks up to water.
        Vector3d bestTarget = findNearestWaterTarget(
                chunkStore,
                chunkStoreStore,
                transform.getPosition(),
                blockX,
                blockY,
                blockZ,
                searchRadius,
                clampedVerticalRadius,
                1.0,
                radiusSq,
                chunkCache
        );
        if (bestTarget == null && consumeRadius > 1.0 + SCORE_EPSILON) {
            bestTarget = findNearestWaterTarget(
                    chunkStore,
                    chunkStoreStore,
                    transform.getPosition(),
                    blockX,
                    blockY,
                    blockZ,
                    searchRadius,
                    clampedVerticalRadius,
                    consumeRadius,
                    radiusSq,
                    chunkCache
            );
        }
        cacheSearchTarget(cacheKey, bestTarget, nowMs);
        return bestTarget;
    }

    @Nullable
    private static Vector3d findNearestWaterTarget(@Nonnull ChunkStore chunkStore,
                                                   @Nonnull Store<ChunkStore> chunkStoreStore,
                                                   @Nonnull Vector3d npcPosition,
                                                   int blockX,
                                                   int blockY,
                                                   int blockZ,
                                                   int searchRadius,
                                                   int verticalScanRadius,
                                                   double consumeRadius,
                                                   double radiusSq,
                                                   @Nonnull Map<Long, WorldChunk> chunkCache) {
        Vector3d bestTarget = null;
        for (int horizontalRadius = 0; horizontalRadius <= searchRadius && bestTarget == null; horizontalRadius++) {
            bestTarget = findNearestWaterTargetInHorizontalRing(
                    chunkStore,
                    chunkStoreStore,
                    npcPosition,
                    blockX,
                    blockY,
                    blockZ,
                    horizontalRadius,
                    verticalScanRadius,
                    consumeRadius,
                    radiusSq,
                    chunkCache
            );
        }
        return bestTarget;
    }

    boolean consumeNearbyWaterTroughCharge(@Nullable Ref<EntityStore> npcRef,
                                           @Nullable Store<EntityStore> store,
                                           @Nonnull TwNeedsConfig config) {
        return consumeNearbyWaterTroughCharge(npcRef, store, config, null);
    }

    boolean consumeNearbyWaterTroughCharge(@Nullable Ref<EntityStore> npcRef,
                                           @Nullable Store<EntityStore> store,
                                           @Nonnull TwNeedsConfig config,
                                           @Nullable Vector3d scanCenterOverride) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        World world = resolveWorld(store);
        if (transform == null || world == null || world.getChunkStore() == null) {
            return false;
        }
        TwNeedsConfig.PassiveRefillSettings passiveRefill = config.getPassiveRefill();
        double radius = passiveRefill.getWaterConsumeRadius();
        int verticalScanRadius = passiveRefill.getWaterVerticalScanRadius();
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return false;
        }
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunkStoreStore = chunkStore.getStore();
        if (chunkStoreStore == null) {
            return false;
        }
        Vector3d scanCenter = scanCenterOverride;
        if (scanCenter == null
                || !Double.isFinite(scanCenter.x)
                || !Double.isFinite(scanCenter.y)
                || !Double.isFinite(scanCenter.z)) {
            scanCenter = transform.getPosition();
        }
        int blockX = (int) Math.floor(scanCenter.x);
        int blockY = (int) Math.floor(scanCenter.y);
        int blockZ = (int) Math.floor(scanCenter.z);
        int searchRadius = Math.max(1, (int) Math.ceil(radius));
        double radiusSq = radius * radius;
        int bestX = 0;
        int bestY = 0;
        int bestZ = 0;
        boolean found = false;
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        Map<Long, WorldChunk> chunkCache = new HashMap<>();
        for (int y = blockY - verticalScanRadius; y <= blockY + verticalScanRadius; y++) {
            for (int x = blockX - searchRadius; x <= blockX + searchRadius; x++) {
                for (int z = blockZ - searchRadius; z <= blockZ + searchRadius; z++) {
                    double dx = x - blockX;
                    double dz = z - blockZ;
                    if ((dx * dx) + (dz * dz) > radiusSq) {
                        continue;
                    }
                    WorldChunk worldChunk = resolveWorldChunk(chunkStore, chunkStoreStore, x, z, chunkCache);
                    if (!isConsumableWaterTroughAt(worldChunk, chunkStoreStore, x, y, z)) {
                        continue;
                    }
                    double distanceSq = distanceSquaredToBlockCenter(scanCenter, x, y, z);
                    if (!Double.isFinite(distanceSq) || distanceSq >= bestDistanceSq) {
                        continue;
                    }
                    bestDistanceSq = distanceSq;
                    bestX = x;
                    bestY = y;
                    bestZ = z;
                    found = true;
                }
            }
        }
        if (!found) {
            return false;
        }
        WorldChunk targetChunk = resolveWorldChunk(chunkStore, chunkStoreStore, bestX, bestZ, chunkCache);
        if (targetChunk == null) {
            return false;
        }
        return FeedTroughWaterStateService.consumeSingleCharge(targetChunk, chunkStoreStore, bestX, bestY, bestZ);
    }

    @Nullable
    Vector3d findNearestFoodContainerPosition(@Nullable Ref<EntityStore> npcRef,
                                              @Nullable Store<EntityStore> store,
                                              @Nonnull TwNeedsConfig config) {
        if (config == null) {
            return null;
        }
        TwNeedsConfig.PassiveRefillSettings passive = config.getPassiveRefill();
        return findNearestFoodContainerPosition(
                npcRef,
                store,
                passive.getContainerSearchRadius(),
                passive.getContainerFoodItemIds(),
                passive.getContainerVerticalScanRadius()
        );
    }

    @Nullable
    public Vector3d findNearestFoodContainerPosition(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store,
                                                     double radius,
                                                     @Nullable String[] allowedItemIds) {
        return findNearestFoodContainerPosition(
                npcRef,
                store,
                radius,
                allowedItemIds,
                DEFAULT_CONTAINER_VERTICAL_SCAN_RADIUS
        );
    }

    @Nullable
    public Vector3d findNearestFoodContainerPosition(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store,
                                                     double radius,
                                                     @Nullable String[] allowedItemIds,
                                                     int verticalScanRadius) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return null;
        }
        Set<String> allowedFoods = normalizeItemIds(allowedItemIds);
        if (allowedFoods.isEmpty()) {
            return null;
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        World world = resolveWorld(store);
        if (transform == null || world == null || world.getChunkStore() == null) {
            return null;
        }
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return null;
        }
        long nowMs = resolveCurrentTimeMs(store);
        NeedsSearchCacheKey cacheKey = buildSearchCacheKey(
                npcRef,
                store,
                ResourceSearchKind.FOOD_CONTAINER,
                transform.getPosition(),
                radius,
                verticalScanRadius,
                allowedFoods
        );
        Vector3d cachedTarget = getCachedSearchTarget(cacheKey, nowMs);
        if (cachedTarget != CACHE_MISS) {
            return cachedTarget;
        }
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunkStoreStore = chunkStore.getStore();
        if (chunkStoreStore == null) {
            return null;
        }

        int blockX = (int) Math.floor(transform.getPosition().x);
        int blockY = (int) Math.floor(transform.getPosition().y);
        int blockZ = (int) Math.floor(transform.getPosition().z);
        int searchRadius = Math.max(1, (int) Math.ceil(radius));
        double radiusSq = radius * radius;
        int clampedVerticalRadius = Math.max(0, verticalScanRadius);
        Map<Long, WorldChunk> chunkCache = new HashMap<>();
        Vector3d bestTarget = null;
        for (int horizontalRadius = 0; horizontalRadius <= searchRadius && bestTarget == null; horizontalRadius++) {
            bestTarget = findNearestFoodTargetInHorizontalRing(
                    chunkStore,
                    chunkStoreStore,
                    transform.getPosition(),
                    blockX,
                    blockY,
                    blockZ,
                    horizontalRadius,
                    clampedVerticalRadius,
                    radiusSq,
                    allowedFoods,
                    chunkCache
            );
        }
        cacheSearchTarget(cacheKey, bestTarget, nowMs);
        return bestTarget;
    }

    @Nullable
    private static Vector3d findNearestWaterTargetInHorizontalRing(@Nonnull ChunkStore chunkStore,
                                                                   @Nonnull Store<ChunkStore> chunkStoreStore,
                                                                   @Nonnull Vector3d npcPosition,
                                                                   int blockX,
                                                                   int blockY,
                                                                   int blockZ,
                                                                   int horizontalRadius,
                                                                   int verticalScanRadius,
                                                                   double consumeRadius,
                                                                   double radiusSq,
                                                                   @Nonnull Map<Long, WorldChunk> chunkCache) {
        Vector3d bestTarget = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (int yOffset = -verticalScanRadius; yOffset <= verticalScanRadius; yOffset++) {
            int y = blockY + yOffset;
            for (int x = blockX - horizontalRadius; x <= blockX + horizontalRadius; x++) {
                for (int z = blockZ - horizontalRadius; z <= blockZ + horizontalRadius; z++) {
                    if (horizontalRadius > 0
                            && x > blockX - horizontalRadius
                            && x < blockX + horizontalRadius
                            && z > blockZ - horizontalRadius
                            && z < blockZ + horizontalRadius) {
                        continue;
                    }
                    double dx = x - blockX;
                    double dz = z - blockZ;
                    if ((dx * dx) + (dz * dz) > radiusSq) {
                        continue;
                    }
                    WorldChunk worldChunk = resolveWorldChunk(chunkStore, chunkStoreStore, x, z, chunkCache);
                    if (worldChunk == null || !isConsumableWaterSourceAt(worldChunk, chunkStoreStore, x, y, z)) {
                        continue;
                    }
                    Vector3d standPosition = findNearestStandPositionNearWaterSource(
                            chunkStore,
                            chunkStoreStore,
                            x,
                            y,
                            z,
                            npcPosition,
                            consumeRadius,
                            chunkCache
                    );
                    if (standPosition == null) {
                        continue;
                    }
                    double distanceSq = distanceSquared(standPosition, npcPosition);
                    if (Double.isFinite(distanceSq) && distanceSq < bestDistanceSq) {
                        bestDistanceSq = distanceSq;
                        bestTarget = standPosition;
                    }
                }
            }
        }
        return bestTarget;
    }

    @Nullable
    private static Vector3d findNearestFoodTargetInHorizontalRing(@Nonnull ChunkStore chunkStore,
                                                                  @Nonnull Store<ChunkStore> chunkStoreStore,
                                                                  @Nonnull Vector3d npcPosition,
                                                                  int blockX,
                                                                  int blockY,
                                                                  int blockZ,
                                                                  int horizontalRadius,
                                                                  int verticalScanRadius,
                                                                  double radiusSq,
                                                                  @Nonnull Set<String> allowedFoods,
                                                                  @Nonnull Map<Long, WorldChunk> chunkCache) {
        Vector3d bestTarget = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (int yOffset = -verticalScanRadius; yOffset <= verticalScanRadius; yOffset++) {
            int y = blockY + yOffset;
            for (int x = blockX - horizontalRadius; x <= blockX + horizontalRadius; x++) {
                for (int z = blockZ - horizontalRadius; z <= blockZ + horizontalRadius; z++) {
                    if (horizontalRadius > 0
                            && x > blockX - horizontalRadius
                            && x < blockX + horizontalRadius
                            && z > blockZ - horizontalRadius
                            && z < blockZ + horizontalRadius) {
                        continue;
                    }
                    double dx = x - blockX;
                    double dz = z - blockZ;
                    if ((dx * dx) + (dz * dz) > radiusSq) {
                        continue;
                    }
                    WorldChunk worldChunk = resolveWorldChunk(chunkStore, chunkStoreStore, x, z, chunkCache);
                    if (worldChunk == null) {
                        continue;
                    }
                    Object containerState = FeedTroughContainerCompat.resolveContainerState(
                            worldChunk,
                            chunkStoreStore,
                            x,
                            y,
                            z
                    );
                    ItemContainer container = FeedTroughContainerCompat.getItemContainer(containerState);
                    if (container == null || !containsAllowedFood(container, allowedFoods)) {
                        continue;
                    }
                    Vector3d standPosition = findNearestStandPositionAdjacentToBlock(
                            chunkStore,
                            chunkStoreStore,
                            x,
                            y,
                            z,
                            npcPosition,
                            chunkCache
                    );
                    if (standPosition == null) {
                        continue;
                    }
                    double distanceSq = distanceSquared(standPosition, npcPosition);
                    if (Double.isFinite(distanceSq) && distanceSq < bestDistanceSq) {
                        bestDistanceSq = distanceSq;
                        bestTarget = standPosition;
                    }
                }
            }
        }
        return bestTarget;
    }

    @Nullable
    private static Vector3d getCachedSearchTarget(@Nullable NeedsSearchCacheKey cacheKey, long nowMs) {
        if (cacheKey == null) {
            return CACHE_MISS;
        }
        CachedSearchResult cached = SEARCH_CACHE.get(cacheKey);
        if (cached == null) {
            return CACHE_MISS;
        }
        if (nowMs >= cached.expiresAtMs()) {
            SEARCH_CACHE.remove(cacheKey, cached);
            return CACHE_MISS;
        }
        return cached.target();
    }

    private static void cacheSearchTarget(@Nullable NeedsSearchCacheKey cacheKey,
                                          @Nullable Vector3d target,
                                          long nowMs) {
        if (cacheKey == null) {
            return;
        }
        long ttlMs = target != null ? SEARCH_CACHE_HIT_TTL_MS : SEARCH_CACHE_MISS_TTL_MS;
        SEARCH_CACHE.put(
                cacheKey,
                new CachedSearchResult(target != null ? new Vector3d(target) : null, nowMs + ttlMs)
        );
        cleanupExpiredSearchCache(nowMs);
    }

    private static void cleanupExpiredSearchCache(long nowMs) {
        if (SEARCH_CACHE.size() < SEARCH_CACHE_MAX_ENTRIES) {
            return;
        }
        SEARCH_CACHE.entrySet().removeIf(entry -> entry == null
                || entry.getKey() == null
                || entry.getValue() == null
                || nowMs >= entry.getValue().expiresAtMs());
    }

    @Nullable
    private static NeedsSearchCacheKey buildSearchCacheKey(@Nullable Ref<EntityStore> npcRef,
                                                           @Nullable Store<EntityStore> store,
                                                           @Nonnull ResourceSearchKind resourceKind,
                                                           @Nullable Vector3d position,
                                                           double radius,
                                                           int verticalScanRadius,
                                                           @Nullable Set<String> normalizedItemIds) {
        if (npcRef == null || store == null || !npcRef.isValid() || position == null) {
            return null;
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        UUID npcUuid = npc != null ? npc.getUuid() : null;
        World world = resolveWorld(store);
        String worldName = world != null ? world.getName() : null;
        if (npcUuid == null || worldName == null || worldName.isBlank()) {
            return null;
        }
        int blockX = (int) Math.floor(position.x);
        int blockY = (int) Math.floor(position.y);
        int blockZ = (int) Math.floor(position.z);
        int radiusKey = Math.max(1, (int) Math.ceil(radius * 10.0));
        int itemIdsHash = normalizedItemIds == null || normalizedItemIds.isEmpty() ? 0 : normalizedItemIds.hashCode();
        return new NeedsSearchCacheKey(
                npcUuid,
                worldName,
                resourceKind,
                blockX,
                blockY,
                blockZ,
                radiusKey,
                Math.max(0, verticalScanRadius),
                itemIdsHash
        );
    }

    private static long resolveCurrentTimeMs(@Nullable Store<EntityStore> store) {
        if (store == null) {
            return System.currentTimeMillis();
        }
        WorldTimeResource worldTime = store.getResource(WorldTimeResource.getResourceType());
        return worldTime != null ? worldTime.getGameTime().toEpochMilli() : System.currentTimeMillis();
    }

    private enum ResourceSearchKind {
        WATER,
        FOOD_CONTAINER
    }

    private record NeedsSearchCacheKey(@Nonnull UUID npcUuid,
                                       @Nonnull String worldName,
                                       @Nonnull ResourceSearchKind resourceKind,
                                       int blockX,
                                       int blockY,
                                       int blockZ,
                                       int radiusKey,
                                       int verticalScanRadius,
                                       int itemIdsHash) {
    }

    private record CachedSearchResult(@Nullable Vector3d target,
                                      long expiresAtMs) {
    }

    @Nullable
    private static final Vector3d CACHE_MISS = new Vector3d(Double.NaN, Double.NaN, Double.NaN);

    int consumeNearbyContainerFood(@Nullable Ref<EntityStore> npcRef,
                                   @Nullable Store<EntityStore> store,
                                   @Nonnull TwNeedsConfig config) {
        return consumeNearbyContainerFood(npcRef, store, config, null);
    }

    int consumeNearbyContainerFood(@Nullable Ref<EntityStore> npcRef,
                                   @Nullable Store<EntityStore> store,
                                   @Nonnull TwNeedsConfig config,
                                   @Nullable String[] preferredFoodItemIds) {
        return consumeNearbyContainerFood(npcRef, store, config, preferredFoodItemIds, Double.NaN);
    }

    int consumeNearbyContainerFood(@Nullable Ref<EntityStore> npcRef,
                                   @Nullable Store<EntityStore> store,
                                   @Nonnull TwNeedsConfig config,
                                   @Nullable String[] preferredFoodItemIds,
                                   double consumeRadiusOverride) {
        return consumeNearbyContainerFoodDetailed(
                npcRef,
                store,
                config,
                null,
                preferredFoodItemIds,
                consumeRadiusOverride,
                null
        ).getConsumedItems();
    }

    @Nonnull
    ContainerConsumeResult consumeNearbyContainerFoodDetailed(@Nullable Ref<EntityStore> npcRef,
                                                              @Nullable Store<EntityStore> store,
                                                              @Nonnull TwNeedsConfig config,
                                                              @Nullable String roleId,
                                                              @Nullable String[] preferredFoodItemIds,
                                                              double consumeRadiusOverride,
                                                              @Nullable Vector3d scanCenterOverride) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return new ContainerConsumeResult(
                    0, 0, 0, 0, 0, 0, 0, 0.0, 0,
                    0, 0, 0,
                    0, 0, 0,
                    false,
                    Double.NaN, Double.NaN,
                    ContainerConsumeStatus.INVALID_CONTEXT
            );
        }
        TwNeedsConfig.PassiveRefillSettings passiveRefill = config.getPassiveRefill();
        int maxItems = passiveRefill.getMaxContainerItemsConsumedPerSweep();
        int verticalScanRadius = passiveRefill.getContainerVerticalScanRadius();
        if (maxItems <= 0) {
            return new ContainerConsumeResult(
                    0, 0, 0, 0, 0, 0, maxItems, 0.0, verticalScanRadius,
                    0, 0, 0,
                    0, 0, 0,
                    false,
                    Double.NaN, Double.NaN,
                    ContainerConsumeStatus.MAX_ITEMS_NON_POSITIVE
            );
        }
        Set<String> allowedFoods = normalizeItemIds(preferredFoodItemIds);
        allowedFoods.addAll(normalizeItemIds(passiveRefill.getContainerFoodItemIds()));
        if (allowedFoods.isEmpty()) {
            return new ContainerConsumeResult(
                    0, 0, 0, 0, 0, 0, maxItems, 0.0, verticalScanRadius,
                    0, 0, 0,
                    0, 0, 0,
                    false,
                    Double.NaN, Double.NaN,
                    ContainerConsumeStatus.ALLOWED_FOODS_EMPTY
            );
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        World world = resolveWorld(store);
        if (transform == null || world == null || world.getChunkStore() == null) {
            return new ContainerConsumeResult(
                    0, 0, 0, 0, 0, 0, maxItems, 0.0, verticalScanRadius,
                    0, 0, 0,
                    0, 0, 0,
                    false,
                    Double.NaN, Double.NaN,
                    ContainerConsumeStatus.WORLD_CONTEXT_MISSING
            );
        }
        double radius = passiveRefill.getContainerConsumeRadius();
        if (Double.isFinite(consumeRadiusOverride) && consumeRadiusOverride > 0.0) {
            radius = consumeRadiusOverride;
        }
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return new ContainerConsumeResult(
                    0, 0, 0, 0, 0, 0, maxItems, radius, verticalScanRadius,
                    0, 0, 0,
                    0, 0, 0,
                    false,
                    Double.NaN, Double.NaN,
                    ContainerConsumeStatus.INVALID_RADIUS
            );
        }
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunkStoreStore = chunkStore.getStore();
        if (chunkStoreStore == null) {
            return new ContainerConsumeResult(
                    0, 0, 0, 0, 0, 0, maxItems, radius, verticalScanRadius,
                    0, 0, 0,
                    0, 0, 0,
                    false,
                    Double.NaN, Double.NaN,
                    ContainerConsumeStatus.CHUNK_STORE_UNAVAILABLE
            );
        }
        int consumed = 0;
        FeedItemPreferenceResolver preferenceResolver = FeedItemPreferenceResolver.create(npcRef, store, roleId);
        Map<String, Integer> consumedByItemId = new HashMap<>();
        int scannedContainers = 0;
        int containersWithAllowedFood = 0;
        int matchingStacksSeen = 0;
        int removalAttempts = 0;
        int removalFailures = 0;
        Vector3d npcPosition = transform.getPosition();
        int npcBlockX = (int) Math.floor(npcPosition.x);
        int npcBlockY = (int) Math.floor(npcPosition.y);
        int npcBlockZ = (int) Math.floor(npcPosition.z);
        boolean scanFromOverride = scanCenterOverride != null
                && Double.isFinite(scanCenterOverride.x)
                && Double.isFinite(scanCenterOverride.y)
                && Double.isFinite(scanCenterOverride.z);
        Vector3d scanCenter = scanFromOverride ? scanCenterOverride : npcPosition;
        int blockX = (int) Math.floor(scanCenter.x);
        int blockY = (int) Math.floor(scanCenter.y);
        int blockZ = (int) Math.floor(scanCenter.z);
        double nearestContainerDistanceSq = Double.POSITIVE_INFINITY;
        double nearestAllowedContainerDistanceSq = Double.POSITIVE_INFINITY;
        int searchRadius = Math.max(1, (int) Math.ceil(radius));
        double radiusSq = radius * radius;
        Map<Long, WorldChunk> chunkCache = new HashMap<>();
        for (int y = blockY - verticalScanRadius; y <= blockY + verticalScanRadius; y++) {
            for (int x = blockX - searchRadius; x <= blockX + searchRadius; x++) {
                for (int z = blockZ - searchRadius; z <= blockZ + searchRadius; z++) {
                    double dx = x - blockX;
                    double dz = z - blockZ;
                    if ((dx * dx) + (dz * dz) > radiusSq) {
                        continue;
                    }
                    WorldChunk worldChunk = resolveWorldChunk(chunkStore, chunkStoreStore, x, z, chunkCache);
                    if (worldChunk == null) {
                        continue;
                    }
                    Object containerState = FeedTroughContainerCompat.resolveContainerState(
                            worldChunk,
                            chunkStoreStore,
                            x,
                            y,
                            z
                    );
                    ItemContainer container = FeedTroughContainerCompat.getItemContainer(containerState);
                    if (container == null) {
                        continue;
                    }
                    scannedContainers++;
                    double containerDistanceSq = distanceSquaredToBlockCenter(scanCenter, x, y, z);
                    if (containerDistanceSq < nearestContainerDistanceSq) {
                        nearestContainerDistanceSq = containerDistanceSq;
                    }
                    if (!containsAllowedFood(container, allowedFoods)) {
                        continue;
                    }
                    containersWithAllowedFood++;
                    if (containerDistanceSq < nearestAllowedContainerDistanceSq) {
                        nearestAllowedContainerDistanceSq = containerDistanceSq;
                    }
                    SlotConsumeResult slotResult = consumeFoodFromContainerDetailed(
                            container,
                            allowedFoods,
                            maxItems - consumed,
                            preferenceResolver
                    );
                    consumed += slotResult.consumed;
                    mergeConsumedItemCounts(consumedByItemId, slotResult.consumedItemCountsByItemId);
                    matchingStacksSeen += slotResult.matchingStacksSeen;
                    removalAttempts += slotResult.removalAttempts;
                    removalFailures += slotResult.removalFailures;
                    if (consumed >= maxItems) {
                        return new ContainerConsumeResult(
                                consumed,
                                consumedByItemId,
                                scannedContainers,
                                containersWithAllowedFood,
                                matchingStacksSeen,
                                removalAttempts,
                                removalFailures,
                                maxItems,
                                radius,
                                verticalScanRadius,
                                blockX,
                                blockY,
                                blockZ,
                                npcBlockX,
                                npcBlockY,
                                npcBlockZ,
                                scanFromOverride,
                                distanceSquaredToDistanceOrNaN(nearestContainerDistanceSq),
                                distanceSquaredToDistanceOrNaN(nearestAllowedContainerDistanceSq),
                                ContainerConsumeStatus.SUCCESS
                        );
                    }
                }
            }
        }
        ContainerConsumeStatus status;
        if (consumed > 0) {
            status = ContainerConsumeStatus.SUCCESS;
        } else if (scannedContainers <= 0) {
            status = ContainerConsumeStatus.NO_CONTAINER_IN_RANGE;
        } else if (containersWithAllowedFood <= 0) {
            status = ContainerConsumeStatus.NO_ALLOWED_FOOD_IN_RANGE;
        } else if (removalAttempts > 0 && removalFailures >= removalAttempts) {
            status = ContainerConsumeStatus.REMOVE_TRANSACTION_FAILED;
        } else {
            status = ContainerConsumeStatus.NO_ITEMS_CONSUMED;
        }
        return new ContainerConsumeResult(
                consumed,
                consumedByItemId,
                scannedContainers,
                containersWithAllowedFood,
                matchingStacksSeen,
                removalAttempts,
                removalFailures,
                maxItems,
                radius,
                verticalScanRadius,
                blockX,
                blockY,
                blockZ,
                npcBlockX,
                npcBlockY,
                npcBlockZ,
                scanFromOverride,
                distanceSquaredToDistanceOrNaN(nearestContainerDistanceSq),
                distanceSquaredToDistanceOrNaN(nearestAllowedContainerDistanceSq),
                status
        );
    }

    boolean isConfiguredWaterBucketItem(@Nullable String itemId, @Nonnull TwNeedsConfig config) {
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        String normalized = itemId.trim().toLowerCase(Locale.ROOT);
        for (String candidate : config.getManualRefill().getWaterBucketItemIds()) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            if (normalized.equals(candidate.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static World resolveWorld(@Nullable Store<EntityStore> store) {
        if (store == null || store.getExternalData() == null) {
            return null;
        }
        return store.getExternalData().getWorld();
    }

    @Nullable
    private static WorldChunk resolveWorldChunk(@Nonnull ChunkStore chunkStore,
                                                @Nonnull Store<ChunkStore> chunkStoreStore,
                                                int blockX,
                                                int blockZ,
                                                @Nonnull Map<Long, WorldChunk> chunkCache) {
        long chunkIndex = ChunkUtil.indexChunkFromBlock(blockX, blockZ);
        if (chunkCache.containsKey(chunkIndex)) {
            return chunkCache.get(chunkIndex);
        }
        Ref<ChunkStore> chunkRef = chunkStore.getChunkReference(chunkIndex);
        if (chunkRef == null || !chunkRef.isValid()) {
            chunkCache.put(chunkIndex, null);
            return null;
        }
        WorldChunk worldChunk = chunkStoreStore.getComponent(chunkRef, WorldChunk.getComponentType());
        chunkCache.put(chunkIndex, worldChunk);
        return worldChunk;
    }

    @Nullable
    private static Vector3d findNearestStandPositionAdjacentToBlock(@Nonnull ChunkStore chunkStore,
                                                                    @Nonnull Store<ChunkStore> chunkStoreStore,
                                                                    int sourceX,
                                                                    int sourceY,
                                                                    int sourceZ,
                                                                    @Nonnull Vector3d npcPosition,
                                                                    @Nonnull Map<Long, WorldChunk> chunkCache) {
        return findNearestStandPositionNearBlock(
                chunkStore,
                chunkStoreStore,
                sourceX,
                sourceY,
                sourceZ,
                npcPosition,
                1.0,
                chunkCache
        );
    }

    @Nullable
    private static Vector3d findNearestStandPositionNearWaterSource(@Nonnull ChunkStore chunkStore,
                                                                    @Nonnull Store<ChunkStore> chunkStoreStore,
                                                                    int sourceX,
                                                                    int sourceY,
                                                                    int sourceZ,
                                                                    @Nonnull Vector3d npcPosition,
                                                                    double consumeRadius,
                                                                    @Nonnull Map<Long, WorldChunk> chunkCache) {
        return findNearestStandPositionNearBlock(
                chunkStore,
                chunkStoreStore,
                sourceX,
                sourceY,
                sourceZ,
                npcPosition,
                Math.max(1.0, consumeRadius),
                chunkCache
        );
    }

    @Nullable
    private static Vector3d findNearestStandPositionNearBlock(@Nonnull ChunkStore chunkStore,
                                                              @Nonnull Store<ChunkStore> chunkStoreStore,
                                                              int sourceX,
                                                              int sourceY,
                                                              int sourceZ,
                                                              @Nonnull Vector3d npcPosition,
                                                              double maxDistance,
                                                              @Nonnull Map<Long, WorldChunk> chunkCache) {
        Vector3d bestTarget = null;
        double bestDistanceSq = Double.MAX_VALUE;
        double normalizedMaxDistance = Double.isFinite(maxDistance) && maxDistance > 0.0 ? maxDistance : 1.0;
        int standRadius = Math.max(1, (int) Math.ceil(normalizedMaxDistance));
        double maxDistanceSq = normalizedMaxDistance * normalizedMaxDistance;
        for (int candidateX = sourceX - standRadius; candidateX <= sourceX + standRadius; candidateX++) {
            for (int candidateZ = sourceZ - standRadius; candidateZ <= sourceZ + standRadius; candidateZ++) {
                double dx = candidateX - sourceX;
                double dz = candidateZ - sourceZ;
                if ((dx * dx) + (dz * dz) > maxDistanceSq) {
                    continue;
                }
                for (int yOffset : STAND_HEIGHT_OFFSETS) {
                    int candidateY = sourceY + yOffset;
                    if (!canStandAt(chunkStore, chunkStoreStore, candidateX, candidateY, candidateZ, chunkCache)) {
                        continue;
                    }
                    Vector3d target = new Vector3d(
                            candidateX + 0.5,
                            candidateY + STAND_POSITION_Y_OFFSET,
                            candidateZ + 0.5
                    );
                    double distanceSq = distanceSquared(target, npcPosition);
                    if (!Double.isFinite(distanceSq) || distanceSq >= bestDistanceSq) {
                        continue;
                    }
                    bestDistanceSq = distanceSq;
                    bestTarget = target;
                }
            }
        }
        return bestTarget;
    }

    private static boolean canStandAt(@Nonnull ChunkStore chunkStore,
                                      @Nonnull Store<ChunkStore> chunkStoreStore,
                                      int blockX,
                                      int blockY,
                                      int blockZ,
                                      @Nonnull Map<Long, WorldChunk> chunkCache) {
        WorldChunk feetChunk = resolveWorldChunk(chunkStore, chunkStoreStore, blockX, blockZ, chunkCache);
        WorldChunk headChunk = resolveWorldChunk(chunkStore, chunkStoreStore, blockX, blockZ, chunkCache);
        WorldChunk groundChunk = resolveWorldChunk(chunkStore, chunkStoreStore, blockX, blockZ, chunkCache);
        if (feetChunk == null || headChunk == null || groundChunk == null) {
            return false;
        }

        int feetFluid = feetChunk.getFluidId(blockX, blockY, blockZ);
        int headFluid = headChunk.getFluidId(blockX, blockY + 1, blockZ);
        int groundFluid = groundChunk.getFluidId(blockX, blockY - 1, blockZ);
        if (feetFluid != 0 || headFluid != 0) {
            return false;
        }

        int feetBlockId = feetChunk.getBlock(blockX, blockY, blockZ);
        int headBlockId = headChunk.getBlock(blockX, blockY + 1, blockZ);
        int groundBlockId = groundChunk.getBlock(blockX, blockY - 1, blockZ);
        if (isSolidBlock(feetBlockId, feetFluid) || isSolidBlock(headBlockId, headFluid)) {
            return false;
        }
        return isSolidBlock(groundBlockId, groundFluid);
    }

    private static boolean isConsumableWaterSourceAt(@Nullable WorldChunk worldChunk,
                                                     @Nullable Store<ChunkStore> chunkStore,
                                                     int blockX,
                                                     int blockY,
                                                     int blockZ) {
        if (worldChunk == null) {
            return false;
        }
        if (worldChunk.getFluidId(blockX, blockY, blockZ) != 0) {
            return true;
        }
        return isConsumableWaterTroughAt(worldChunk, chunkStore, blockX, blockY, blockZ);
    }

    private static boolean isConsumableWaterTroughAt(@Nullable WorldChunk worldChunk,
                                                     @Nullable Store<ChunkStore> chunkStore,
                                                     int blockX,
                                                     int blockY,
                                                     int blockZ) {
        if (worldChunk == null) {
            return false;
        }
        BlockType blockType = worldChunk.getBlockType(blockX, blockY, blockZ);
        return FeedTroughWaterStateService.hasConsumableWater(
                worldChunk,
                chunkStore,
                blockX,
                blockY,
                blockZ,
                blockType
        );
    }

    private static boolean isSolidBlock(int blockId, int fluidId) {
        if (blockId == 0) {
            return false;
        }
        BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
        if (blockType == null || blockType == BlockType.UNKNOWN) {
            return false;
        }
        return WorldUtil.isSolidOnlyBlock(blockType, fluidId);
    }

    private static boolean containsAllowedFood(@Nullable ItemContainer container,
                                               @Nonnull Set<String> allowedFoods) {
        if (container == null || allowedFoods.isEmpty()) {
            return false;
        }
        short capacity = container.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            ItemStack stack = container.getItemStack(slot);
            if (ItemStack.isEmpty(stack)) {
                continue;
            }
            String itemId = stack.getItemId();
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            if (allowedFoods.contains(itemId.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static int consumeFoodFromContainer(@Nullable ItemContainer container,
                                                @Nonnull Set<String> allowedFoods,
                                                int maxItems) {
        return consumeFoodFromContainerDetailed(
                container,
                allowedFoods,
                maxItems,
                FeedItemPreferenceResolver.create((TwHappinessConfig) null)
        ).consumed;
    }

    @Nonnull
    private static SlotConsumeResult consumeFoodFromContainerDetailed(@Nullable ItemContainer container,
                                                                      @Nonnull Set<String> allowedFoods,
                                                                      int maxItems,
                                                                      @Nonnull FeedItemPreferenceResolver preferenceResolver) {
        if (container == null || maxItems <= 0 || allowedFoods.isEmpty()) {
            return new SlotConsumeResult(0, Map.of(), 0, 0, 0);
        }
        int consumed = 0;
        Map<String, Integer> consumedByItemId = new HashMap<>();
        Set<Short> matchingSlotsSeen = new HashSet<>();
        int removalAttempts = 0;
        int removalFailures = 0;
        Set<Short> failedSlots = new HashSet<>();
        while (consumed < maxItems) {
            BestFoodSlotCandidate bestCandidate = findBestFoodSlotCandidate(
                    container,
                    allowedFoods,
                    preferenceResolver,
                    matchingSlotsSeen,
                    failedSlots
            );
            if (bestCandidate == null) {
                break;
            }
            removalAttempts++;
            ItemStackSlotTransaction transaction = container.removeItemStackFromSlot(bestCandidate.slot, 1, false, true);
            if (transaction == null || !transaction.succeeded()) {
                removalFailures++;
                failedSlots.add(bestCandidate.slot);
                continue;
            }
            consumed++;
            consumedByItemId.merge(bestCandidate.normalizedItemId, 1, Integer::sum);
        }
        return new SlotConsumeResult(
                consumed,
                consumedByItemId,
                matchingSlotsSeen.size(),
                removalAttempts,
                removalFailures
        );
    }

    @Nullable
    private static BestFoodSlotCandidate findBestFoodSlotCandidate(@Nonnull ItemContainer container,
                                                                    @Nonnull Set<String> allowedFoods,
                                                                    @Nonnull FeedItemPreferenceResolver preferenceResolver,
                                                                    @Nonnull Set<Short> matchingSlotsSeen,
                                                                    @Nonnull Set<Short> failedSlots) {
        short capacity = container.getCapacity();
        BestFoodSlotCandidate best = null;
        for (short slot = 0; slot < capacity; slot++) {
            if (failedSlots.contains(slot)) {
                continue;
            }
            ItemStack stack = container.getItemStack(slot);
            if (ItemStack.isEmpty(stack)) {
                continue;
            }
            String itemId = stack.getItemId();
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            String normalizedItemId = itemId.trim().toLowerCase(Locale.ROOT);
            if (!allowedFoods.contains(normalizedItemId)) {
                continue;
            }
            matchingSlotsSeen.add(slot);
            double score = preferenceResolver.score(normalizedItemId);
            if (best == null
                    || score > best.score + SCORE_EPSILON
                    || (Math.abs(score - best.score) <= SCORE_EPSILON && slot < best.slot)) {
                best = new BestFoodSlotCandidate(slot, normalizedItemId, score);
            }
        }
        return best;
    }

    private static Set<String> normalizeItemIds(@Nullable String[] itemIds) {
        Set<String> normalized = new HashSet<>();
        if (itemIds == null || itemIds.length == 0) {
            return normalized;
        }
        for (String itemId : itemIds) {
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            normalized.add(itemId.trim().toLowerCase(Locale.ROOT));
        }
        return normalized;
    }

    private static void mergeConsumedItemCounts(@Nonnull Map<String, Integer> aggregate,
                                                @Nonnull Map<String, Integer> additions) {
        if (additions.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Integer> entry : additions.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            if (entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            aggregate.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    private static final class BestFoodSlotCandidate {
        private final short slot;
        @Nonnull
        private final String normalizedItemId;
        private final double score;

        private BestFoodSlotCandidate(short slot, @Nonnull String normalizedItemId, double score) {
            this.slot = slot;
            this.normalizedItemId = normalizedItemId;
            this.score = score;
        }
    }

    private static double distanceSquared(@Nonnull Vector3d left, @Nonnull Vector3d right) {
        double dx = left.x - right.x;
        double dy = left.y - right.y;
        double dz = left.z - right.z;
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    private static double distanceSquaredToBlockCenter(@Nonnull Vector3d origin,
                                                       int blockX,
                                                       int blockY,
                                                       int blockZ) {
        double dx = origin.x - (blockX + 0.5);
        double dy = origin.y - (blockY + 0.5);
        double dz = origin.z - (blockZ + 0.5);
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    private static double distanceSquaredToDistanceOrNaN(double squaredDistance) {
        if (!Double.isFinite(squaredDistance) || squaredDistance == Double.POSITIVE_INFINITY) {
            return Double.NaN;
        }
        if (squaredDistance < 0.0) {
            return Double.NaN;
        }
        return Math.sqrt(squaredDistance);
    }
}
