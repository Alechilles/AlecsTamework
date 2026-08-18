package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwFoodConfig;
import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.items.FeedTroughContainerCompat;
import com.alechilles.alecstamework.items.FeedTroughWaterStateService;
import com.alechilles.alecstamework.performance.RuntimePressureDomain;
import com.alechilles.alecstamework.performance.TameworkRuntimePressureService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
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
    private static final int SEARCH_CACHE_MAX_ENTRIES = 8192;
    private static final double SOURCE_TARGET_CENTER_OFFSET = 0.5;
    private static final double DEFAULT_APPROACH_RADIUS = 2.0;
    private static final double SCORE_EPSILON = 0.000001;
    private static final boolean WATER_STAND_TARGETS_INCLUDE_SOURCE_BLOCK = false;
    private static final NeedsWaterTargetSearchService WATER_TARGET_SEARCH_SERVICE =
            new NeedsWaterTargetSearchService();
    private static final NeedsFoodTargetSearchService FOOD_TARGET_SEARCH_SERVICE =
            new NeedsFoodTargetSearchService();
    private static final ConcurrentHashMap<NeedsSearchCacheKey, CachedSearchResult> SEARCH_CACHE = new ConcurrentHashMap<>();
    private static final NeedsResourceAreaSearchCache AREA_SEARCH_CACHE =
            new NeedsResourceAreaSearchCache(SEARCH_CACHE_MAX_ENTRIES);
    private static final ConcurrentHashMap<Integer, Boolean> WATER_TROUGH_BLOCK_ID_CACHE = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface TargetRejector extends NeedsResourceStandTargetSelector.CandidateRejector {
        @Override
        boolean rejects(@Nonnull Vector3d target);
    }

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

    private record TargetBlock(@Nonnull WorldChunk worldChunk,
                               @Nonnull Store<ChunkStore> chunkStoreStore,
                               int x,
                               int y,
                               int z) {
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
        Vector3d scanCenter = scanCenterOverride;
        if (scanCenter == null
                || !Double.isFinite(scanCenter.x)
                || !Double.isFinite(scanCenter.y)
                || !Double.isFinite(scanCenter.z)) {
            scanCenter = transform.getPosition();
        }
        NeedsWaterTargetSearchService.WaterRequest request =
                new NeedsWaterTargetSearchService.WaterRequest(
                        scanCenter.x,
                        scanCenter.y,
                        scanCenter.z,
                        radius,
                        verticalScanRadius,
                        radius,
                        0
                );
        return WATER_TARGET_SEARCH_SERVICE.search(store, npcRef, request).foundSource();
    }

    public boolean hasConsumableWaterSourceInRange(@Nullable Ref<EntityStore> npcRef,
                                                   @Nullable Store<EntityStore> store,
                                                   double radius,
                                                   int verticalScanRadius) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        World world = resolveWorld(store);
        if (transform == null || world == null || world.getChunkStore() == null) {
            return false;
        }
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return false;
        }
        NeedsWaterTargetSearchService.WaterRequest request =
                new NeedsWaterTargetSearchService.WaterRequest(
                        transform.getPosition().x,
                        transform.getPosition().y,
                        transform.getPosition().z,
                        radius,
                        verticalScanRadius,
                        radius,
                        0
                );
        return WATER_TARGET_SEARCH_SERVICE.search(store, npcRef, request).foundSource();
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
        return findNearestWaterDrinkingTarget(npcRef, store, radius, verticalScanRadius, consumeRadius).target();
    }

    @Nonnull
    public WaterTargetSearchResult findNearestWaterDrinkingTarget(@Nullable Ref<EntityStore> npcRef,
                                                                  @Nullable Store<EntityStore> store,
                                                                  double radius,
                                                                  int verticalScanRadius,
                                                                  double consumeRadius) {
        return findNearestWaterDrinkingTarget(npcRef, null, store, radius, verticalScanRadius, consumeRadius);
    }

    @Nonnull
    public WaterTargetSearchResult findNearestWaterDrinkingTarget(@Nullable Ref<EntityStore> npcRef,
                                                                  @Nullable Role role,
                                                                  @Nullable Store<EntityStore> store,
                                                                  double radius,
                                                                  int verticalScanRadius,
                                                                  double consumeRadius) {
        return findNearestWaterDrinkingTarget(npcRef, role, store, radius, verticalScanRadius, consumeRadius, null);
    }

    @Nonnull
    public WaterTargetSearchResult findNearestWaterDrinkingTarget(@Nullable Ref<EntityStore> npcRef,
                                                                  @Nullable Role role,
                                                                  @Nullable Store<EntityStore> store,
                                                                  double radius,
                                                                  int verticalScanRadius,
                                                                  double consumeRadius,
                                                                  @Nullable TargetRejector targetRejector) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return WaterTargetSearchResult.miss(false);
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        World world = resolveWorld(store);
        if (transform == null || world == null || world.getChunkStore() == null) {
            return WaterTargetSearchResult.miss(false);
        }
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return WaterTargetSearchResult.miss(false);
        }
        long nowMs = resolveCurrentTimeMs();
        double effectiveConsumeRadius = Double.isFinite(consumeRadius) && consumeRadius > 1.0
                ? consumeRadius
                : 1.0;
        NeedsSearchCacheKey cacheKey = buildSearchCacheKey(
                npcRef,
                store,
                ResourceSearchKind.WATER,
                transform.getPosition(),
                radius,
                verticalScanRadius,
                consumeRadius,
                null
        );
        NeedsResourceAreaSearchCache.AreaKey areaCacheKey = buildAreaSearchCacheKey(
                store,
                ResourceSearchKind.WATER,
                transform.getPosition(),
                radius,
                verticalScanRadius,
                consumeRadius,
                List.of()
        );
        if (targetRejector == null) {
            WaterTargetSearchResult cachedResult = getCachedWaterSearchResult(
                    cacheKey,
                    transform.getPosition(),
                    radius,
                    verticalScanRadius,
                    nowMs
            );
            if (cachedResult != null) {
                return cachedResult;
            }
        } else {
            WaterTargetSearchResult cachedResult = getCachedWaterSearchResult(
                    cacheKey,
                    transform.getPosition(),
                    radius,
                    verticalScanRadius,
                    nowMs
            );
            if (cachedResult != null
                    && (cachedResult.target() == null || !targetRejector.rejects(cachedResult.target()))) {
                return cachedResult;
            }
        }
        WaterTargetSearchResult areaCachedResult = getAreaCachedWaterSearchResult(
                areaCacheKey,
                transform.getPosition(),
                radius,
                verticalScanRadius,
                targetRejector,
                nowMs
        );
        if (areaCachedResult != null) {
            if (targetRejector == null || shouldCacheRejectorWaterSearchResult(areaCachedResult)) {
                cacheWaterSearchResult(cacheKey, areaCachedResult, nowMs);
            }
            return areaCachedResult;
        }
        NeedsWaterTargetSearchService.WaterRequest request =
                new NeedsWaterTargetSearchService.WaterRequest(
                        transform.getPosition().x,
                        transform.getPosition().y,
                        transform.getPosition().z,
                        radius,
                        verticalScanRadius,
                        effectiveConsumeRadius,
                        NeedsResourceCandidates.MAX_CANDIDATES
                );
        long startedNs = System.nanoTime();
        NeedsResourceCandidates.Snapshot snapshot = WATER_TARGET_SEARCH_SERVICE.search(store, npcRef, request);
        WaterTargetSearchResult bestResult = toWaterTargetResult(
                snapshot,
                transform.getPosition(),
                radius,
                verticalScanRadius,
                targetRejector
        );
        recordResourceSearchWork(startedNs, nowMs);
        if (targetRejector == null || shouldCacheRejectorWaterSearchResult(bestResult)) {
            cacheWaterSearchResult(cacheKey, bestResult, nowMs);
        }
        if (targetRejector == null || bestResult.target() != null || !snapshot.foundSource()) {
            cacheAreaWaterSearchResult(areaCacheKey, snapshot, nowMs);
        }
        return bestResult;
    }

    boolean consumeNearbyWaterTroughCharge(@Nullable Ref<EntityStore> npcRef,
                                           @Nullable Store<EntityStore> store,
                                           @Nonnull TwNeedsConfig config) {
        return consumeNearbyWaterTroughCharge(npcRef, store, config, null);
    }

    boolean consumeWaterTroughChargeAt(@Nullable Store<EntityStore> store,
                                       @Nullable Vector3d consumeOrigin) {
        TargetBlock target = resolveTargetBlock(store, consumeOrigin);
        if (target == null || !isConsumableWaterTroughAt(
                target.worldChunk(),
                target.chunkStoreStore(),
                target.x(),
                target.y(),
                target.z()
        )) {
            return false;
        }
        return FeedTroughWaterStateService.consumeSingleCharge(
                target.worldChunk(),
                target.chunkStoreStore(),
                target.x(),
                target.y(),
                target.z()
        );
    }

    boolean isConsumableWaterAt(@Nullable Store<EntityStore> store,
                                @Nullable Vector3d consumeOrigin) {
        TargetBlock target = resolveTargetBlock(store, consumeOrigin);
        return target != null && isConsumableWaterSourceAt(
                target.worldChunk(),
                target.chunkStoreStore(),
                target.x(),
                target.y(),
                target.z()
        );
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
        return findNearestFoodContainerTarget(
                npcRef,
                store,
                passive.getContainerSearchRadius(),
                passive.getContainerFoodItemIds(),
                passive.getContainerVerticalScanRadius(),
                passive.getContainerConsumeRadius()
        ).target();
    }

    @Nullable
    public Vector3d findNearestFoodContainerPosition(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store,
                                                     double radius,
                                                     @Nullable String[] allowedItemIds) {
        return findNearestFoodContainerTarget(
                npcRef,
                store,
                radius,
                allowedItemIds,
                DEFAULT_CONTAINER_VERTICAL_SCAN_RADIUS,
                0.0
        ).target();
    }

    @Nullable
    public Vector3d findNearestFoodContainerPosition(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store,
                                                     double radius,
                                                     @Nullable String[] allowedItemIds,
                                                     int verticalScanRadius) {
        return findNearestFoodContainerTarget(
                npcRef,
                null,
                store,
                radius,
                allowedItemIds,
                verticalScanRadius,
                0.0,
                null
        ).target();
    }

    @Nullable
    public Vector3d findNearestFoodContainerPosition(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Role role,
                                                     @Nullable Store<EntityStore> store,
                                                     double radius,
                                                     @Nullable String[] allowedItemIds,
                                                     int verticalScanRadius) {
        return findNearestFoodContainerTarget(
                npcRef,
                role,
                store,
                radius,
                allowedItemIds,
                verticalScanRadius,
                0.0,
                null
        ).target();
    }

    @Nullable
    public Vector3d findNearestFoodContainerPosition(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Role role,
                                                     @Nullable Store<EntityStore> store,
                                                     double radius,
                                                     @Nullable String[] allowedItemIds,
                                                     int verticalScanRadius,
                                                     @Nullable TargetRejector targetRejector) {
        return findNearestFoodContainerTarget(
                npcRef,
                role,
                store,
                radius,
                allowedItemIds,
                verticalScanRadius,
                0.0,
                targetRejector
        ).target();
    }

    @Nonnull
    public FoodTargetSearchResult findNearestFoodContainerTarget(@Nullable Ref<EntityStore> npcRef,
                                                                 @Nullable Store<EntityStore> store,
                                                                 double radius,
                                                                 @Nullable String[] allowedItemIds,
                                                                 int verticalScanRadius,
                                                                 double consumeRadius) {
        return findNearestFoodContainerTarget(
                npcRef,
                null,
                store,
                radius,
                allowedItemIds,
                verticalScanRadius,
                consumeRadius,
                null
        );
    }

    @Nonnull
    public FoodTargetSearchResult findNearestFoodContainerTarget(@Nullable Ref<EntityStore> npcRef,
                                                                 @Nullable Role role,
                                                                 @Nullable Store<EntityStore> store,
                                                                 double radius,
                                                                 @Nullable String[] allowedItemIds,
                                                                 int verticalScanRadius,
                                                                 double consumeRadius,
                                                                 @Nullable TargetRejector targetRejector) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return FoodTargetSearchResult.miss(false);
        }
        Set<String> allowedFoods = normalizeItemIds(allowedItemIds);
        if (allowedFoods.isEmpty()) {
            return FoodTargetSearchResult.miss(false);
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        World world = resolveWorld(store);
        if (transform == null || world == null || world.getChunkStore() == null) {
            return FoodTargetSearchResult.miss(false);
        }
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return FoodTargetSearchResult.miss(false);
        }
        long nowMs = resolveCurrentTimeMs();
        double effectiveConsumeRadius = Double.isFinite(consumeRadius) && consumeRadius > 0.0 ? consumeRadius : 0.0;
        List<String> canonicalFoodIds = new ArrayList<>(allowedFoods);
        NeedsSearchCacheKey cacheKey = buildSearchCacheKey(
                npcRef,
                store,
                ResourceSearchKind.FOOD_CONTAINER,
                transform.getPosition(),
                radius,
                verticalScanRadius,
                effectiveConsumeRadius,
                allowedFoods
        );
        NeedsResourceAreaSearchCache.AreaKey areaCacheKey = buildAreaSearchCacheKey(
                store,
                ResourceSearchKind.FOOD_CONTAINER,
                transform.getPosition(),
                radius,
                verticalScanRadius,
                effectiveConsumeRadius,
                canonicalFoodIds
        );
        if (targetRejector == null) {
            FoodTargetSearchResult cachedResult = getCachedFoodSearchResult(
                    cacheKey,
                    transform.getPosition(),
                    radius,
                    verticalScanRadius,
                    nowMs
            );
            if (cachedResult != null) {
                return cachedResult;
            }
        } else {
            FoodTargetSearchResult cachedResult = getCachedFoodSearchResult(
                    cacheKey,
                    transform.getPosition(),
                    radius,
                    verticalScanRadius,
                    nowMs
            );
            if (cachedResult != null
                    && (cachedResult.target() == null || !targetRejector.rejects(cachedResult.target()))) {
                return cachedResult;
            }
        }
        FoodTargetSearchResult areaCachedResult = getAreaCachedFoodSearchResult(
                areaCacheKey,
                transform.getPosition(),
                radius,
                verticalScanRadius,
                targetRejector,
                nowMs
        );
        if (areaCachedResult != null) {
            if (targetRejector == null || shouldCacheRejectorFoodSearchResult(areaCachedResult)) {
                cacheFoodSearchResult(cacheKey, areaCachedResult, nowMs);
            }
            return areaCachedResult;
        }
        NeedsFoodTargetSearchService.FoodRequest request =
                new NeedsFoodTargetSearchService.FoodRequest(
                        transform.getPosition().x,
                        transform.getPosition().y,
                        transform.getPosition().z,
                        radius,
                        verticalScanRadius,
                        effectiveConsumeRadius,
                        NeedsResourceCandidates.MAX_CANDIDATES,
                        canonicalFoodIds
                );
        long startedNs = System.nanoTime();
        NeedsResourceCandidates.Snapshot snapshot = FOOD_TARGET_SEARCH_SERVICE.search(store, npcRef, request);
        FoodTargetSearchResult bestResult = toFoodTargetResult(
                snapshot,
                transform.getPosition(),
                radius,
                verticalScanRadius,
                targetRejector
        );
        recordResourceSearchWork(startedNs, nowMs);
        if (targetRejector == null || shouldCacheRejectorFoodSearchResult(bestResult)) {
            cacheFoodSearchResult(cacheKey, bestResult, nowMs);
        }
        if (targetRejector == null || bestResult.target() != null || !snapshot.foundSource()) {
            cacheAreaFoodSearchResult(areaCacheKey, snapshot, nowMs);
        }
        return bestResult;
    }

    public boolean hasFoodContainerWithAllowedFoodInRange(@Nullable Ref<EntityStore> npcRef,
                                                          @Nullable Store<EntityStore> store,
                                                          double radius,
                                                          @Nullable String[] allowedItemIds,
                                                          int verticalScanRadius) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        Set<String> allowedFoods = normalizeItemIds(allowedItemIds);
        if (allowedFoods.isEmpty()) {
            return false;
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        World world = resolveWorld(store);
        if (transform == null || world == null || world.getChunkStore() == null) {
            return false;
        }
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return false;
        }
        return FOOD_TARGET_SEARCH_SERVICE.search(
                store,
                npcRef,
                new NeedsFoodTargetSearchService.FoodRequest(
                        transform.getPosition().x,
                        transform.getPosition().y,
                        transform.getPosition().z,
                        radius,
                        verticalScanRadius,
                        radius,
                        0,
                        new ArrayList<>(allowedFoods)
                )
        ).foundSource();
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

    @Nullable
    private static WaterTargetSearchResult getCachedWaterSearchResult(@Nullable NeedsSearchCacheKey cacheKey,
                                                                      @Nullable Vector3d currentPosition,
                                                                      double radius,
                                                                      int verticalScanRadius,
                                                                      long nowMs) {
        if (cacheKey == null) {
            return null;
        }
        CachedSearchResult cached = SEARCH_CACHE.get(cacheKey);
        if (cached == null) {
            return null;
        }
        if (nowMs >= cached.expiresAtMs()) {
            SEARCH_CACHE.remove(cacheKey, cached);
            return null;
        }
        if (!NeedsResourceSearchCachePolicy.isCachedTargetUsable(
                currentPosition,
                cached.target(),
                radius,
                verticalScanRadius
        )) {
            SEARCH_CACHE.remove(cacheKey, cached);
            return null;
        }
        return new WaterTargetSearchResult(
                cached.target() != null ? new Vector3d(cached.target()) : null,
                cached.foundConsumableSource(),
                cached.foundConsumableSourceInConsumeRange(),
                cached.approachRadius()
        );
    }

    @Nullable
    private static FoodTargetSearchResult getCachedFoodSearchResult(@Nullable NeedsSearchCacheKey cacheKey,
                                                                    @Nullable Vector3d currentPosition,
                                                                    double radius,
                                                                    int verticalScanRadius,
                                                                    long nowMs) {
        if (cacheKey == null) {
            return null;
        }
        CachedSearchResult cached = SEARCH_CACHE.get(cacheKey);
        if (cached == null) {
            return null;
        }
        if (nowMs >= cached.expiresAtMs()) {
            SEARCH_CACHE.remove(cacheKey, cached);
            return null;
        }
        if (!NeedsResourceSearchCachePolicy.isCachedTargetUsable(
                currentPosition,
                cached.target(),
                radius,
                verticalScanRadius
        )) {
            SEARCH_CACHE.remove(cacheKey, cached);
            return null;
        }
        return new FoodTargetSearchResult(
                cached.target() != null ? new Vector3d(cached.target()) : null,
                cached.foundConsumableSource(),
                cached.foundConsumableSourceInConsumeRange(),
                cached.approachRadius()
        );
    }

    @Nullable
    private static NeedsResourceAreaSearchCache.AreaKey buildAreaSearchCacheKey(@Nullable Store<EntityStore> store,
                                                                                @Nonnull ResourceSearchKind resourceKind,
                                                                                @Nullable Vector3d position,
                                                                                double radius,
                                                                                int verticalScanRadius,
                                                                                double consumeRadius,
                                                                                @Nullable List<String> itemIds) {
        World world = resolveWorld(store);
        String worldName = world != null ? world.getName() : null;
        return NeedsResourceAreaSearchCache.AreaKey.from(
                worldName,
                resourceKind.name(),
                position,
                radius,
                verticalScanRadius,
                consumeRadius,
                itemIds
        );
    }

    @Nullable
    private static WaterTargetSearchResult getAreaCachedWaterSearchResult(
            @Nullable NeedsResourceAreaSearchCache.AreaKey cacheKey,
            @Nullable Vector3d currentPosition,
            double radius,
            int verticalScanRadius,
            @Nullable TargetRejector targetRejector,
            long nowMs) {
        NeedsResourceCandidates.Snapshot snapshot = AREA_SEARCH_CACHE.getSnapshot(cacheKey, nowMs);
        if (snapshot == null) {
            return null;
        }
        if (snapshot.hasCandidates()
                && selectCandidate(snapshot, currentPosition, radius, verticalScanRadius, null) == null) {
            return null;
        }
        return toWaterTargetResult(
                snapshot,
                currentPosition,
                radius,
                verticalScanRadius,
                targetRejector
        );
    }

    @Nullable
    private static FoodTargetSearchResult getAreaCachedFoodSearchResult(
            @Nullable NeedsResourceAreaSearchCache.AreaKey cacheKey,
            @Nullable Vector3d currentPosition,
            double radius,
            int verticalScanRadius,
            @Nullable TargetRejector targetRejector,
            long nowMs) {
        NeedsResourceCandidates.Snapshot snapshot = AREA_SEARCH_CACHE.getSnapshot(cacheKey, nowMs);
        if (snapshot == null) {
            return null;
        }
        if (snapshot.hasCandidates()
                && selectCandidate(snapshot, currentPosition, radius, verticalScanRadius, null) == null) {
            return null;
        }
        return toFoodTargetResult(
                snapshot,
                currentPosition,
                radius,
                verticalScanRadius,
                targetRejector
        );
    }

    @Nonnull
    private static WaterTargetSearchResult toWaterTargetResult(
            @Nonnull NeedsResourceCandidates.Snapshot snapshot,
            @Nullable Vector3d currentPosition,
            double radius,
            int verticalScanRadius,
            @Nullable TargetRejector targetRejector) {
        NeedsResourceCandidates.Candidate candidate = selectCandidate(
                snapshot,
                currentPosition,
                radius,
                verticalScanRadius,
                targetRejector
        );
        if (candidate == null) {
            return WaterTargetSearchResult.miss(
                    snapshot.foundSource(),
                    snapshot.sourceInConsumeRange()
            );
        }
        return new WaterTargetSearchResult(
                sourceBlockTarget(candidate.x(), candidate.y(), candidate.z()),
                snapshot.foundSource(),
                snapshot.sourceInConsumeRange(),
                candidate.approachRadius()
        );
    }

    @Nonnull
    private static FoodTargetSearchResult toFoodTargetResult(
            @Nonnull NeedsResourceCandidates.Snapshot snapshot,
            @Nullable Vector3d currentPosition,
            double radius,
            int verticalScanRadius,
            @Nullable TargetRejector targetRejector) {
        NeedsResourceCandidates.Candidate candidate = selectCandidate(
                snapshot,
                currentPosition,
                radius,
                verticalScanRadius,
                targetRejector
        );
        if (candidate == null) {
            return FoodTargetSearchResult.miss(
                    snapshot.foundSource(),
                    snapshot.sourceInConsumeRange()
            );
        }
        return new FoodTargetSearchResult(
                sourceBlockTarget(candidate.x(), candidate.y(), candidate.z()),
                snapshot.foundSource(),
                snapshot.sourceInConsumeRange(),
                candidate.approachRadius()
        );
    }

    @Nullable
    private static NeedsResourceCandidates.Candidate selectCandidate(
            @Nonnull NeedsResourceCandidates.Snapshot snapshot,
            @Nullable Vector3d currentPosition,
            double radius,
            int verticalScanRadius,
            @Nullable TargetRejector targetRejector) {
        if (currentPosition == null
                || !Double.isFinite(currentPosition.x)
                || !Double.isFinite(currentPosition.y)
                || !Double.isFinite(currentPosition.z)
                || !Double.isFinite(radius)
                || radius <= 0.0) {
            return null;
        }
        double radiusSq = radius * radius;
        int maxVerticalDelta = Math.max(0, verticalScanRadius);
        Vector3d rejectProbe = targetRejector == null ? null : new Vector3d();
        for (NeedsResourceCandidates.Candidate candidate : snapshot.candidates()) {
            double dx = candidate.x() + SOURCE_TARGET_CENTER_OFFSET - currentPosition.x;
            double dz = candidate.z() + SOURCE_TARGET_CENTER_OFFSET - currentPosition.z;
            if ((dx * dx) + (dz * dz) > radiusSq + SCORE_EPSILON
                    || Math.abs(candidate.y() - Math.floor(currentPosition.y)) > maxVerticalDelta) {
                continue;
            }
            if (targetRejector != null) {
                rejectProbe.set(
                        candidate.x() + SOURCE_TARGET_CENTER_OFFSET,
                        candidate.y() + SOURCE_TARGET_CENTER_OFFSET,
                        candidate.z() + SOURCE_TARGET_CENTER_OFFSET
                );
                if (targetRejector.rejects(rejectProbe)) {
                    continue;
                }
            }
            return candidate;
        }
        return null;
    }

    private static void cacheSearchTarget(@Nullable NeedsSearchCacheKey cacheKey,
                                          @Nullable Vector3d target,
                                          long nowMs) {
        if (cacheKey == null) {
            return;
        }
        long ttlMs = searchCacheTtlMs(target != null);
        SEARCH_CACHE.put(
                cacheKey,
                new CachedSearchResult(
                        target != null ? new Vector3d(target) : null,
                        target != null,
                        target != null,
                        DEFAULT_APPROACH_RADIUS,
                        nowMs + ttlMs
                )
        );
        cleanupExpiredSearchCache(nowMs);
    }

    private static void cacheWaterSearchResult(@Nullable NeedsSearchCacheKey cacheKey,
                                               @Nonnull WaterTargetSearchResult result,
                                               long nowMs) {
        if (cacheKey == null) {
            return;
        }
        long ttlMs = searchCacheTtlMs(result.target() != null, result.foundConsumableSource());
        SEARCH_CACHE.put(
                cacheKey,
                new CachedSearchResult(
                        result.target() != null ? new Vector3d(result.target()) : null,
                        result.foundConsumableSource(),
                        result.foundConsumableSourceInConsumeRange(),
                        result.approachRadius(),
                        nowMs + ttlMs
                )
        );
        cleanupExpiredSearchCache(nowMs);
    }

    private static void cacheAreaWaterSearchResult(@Nullable NeedsResourceAreaSearchCache.AreaKey cacheKey,
                                                   @Nonnull NeedsResourceCandidates.Snapshot snapshot,
                                                   long nowMs) {
        AREA_SEARCH_CACHE.put(cacheKey, snapshot, nowMs);
    }

    private static void cacheAreaFoodSearchResult(@Nullable NeedsResourceAreaSearchCache.AreaKey cacheKey,
                                                  @Nonnull NeedsResourceCandidates.Snapshot snapshot,
                                                  long nowMs) {
        AREA_SEARCH_CACHE.put(cacheKey, snapshot, nowMs);
    }

    private static void cacheFoodSearchResult(@Nullable NeedsSearchCacheKey cacheKey,
                                              @Nonnull FoodTargetSearchResult result,
                                              long nowMs) {
        if (cacheKey == null) {
            return;
        }
        long ttlMs = searchCacheTtlMs(result.target() != null, result.foundConsumableSource());
        SEARCH_CACHE.put(
                cacheKey,
                new CachedSearchResult(
                        result.target() != null ? new Vector3d(result.target()) : null,
                        result.foundConsumableSource(),
                        result.foundConsumableSourceInConsumeRange(),
                        result.approachRadius(),
                        nowMs + ttlMs
                )
        );
        cleanupExpiredSearchCache(nowMs);
    }

    static long searchCacheTtlMs(boolean hasTarget) {
        return searchCacheTtlMs(hasTarget, true);
    }

    static long searchCacheTtlMs(boolean hasTarget, boolean foundConsumableSource) {
        return searchCacheTtlMs(hasTarget, foundConsumableSource, resolveCurrentTimeMs());
    }

    static long searchCacheTtlMs(boolean hasTarget, boolean foundConsumableSource, long nowMs) {
        long baseTtlMs = NeedsResourceSearchCachePolicy.baseTtlMs(hasTarget, foundConsumableSource);
        return TameworkRuntimePressureService.getInstance().scaleTtlMs(
                RuntimePressureDomain.NEEDS_RESOURCE_SEARCH,
                baseTtlMs,
                nowMs
        );
    }

    static boolean shouldRunExpandedWaterSearchForTests(@Nonnull WaterTargetSearchResult primaryResult,
                                                        double consumeRadius) {
        return shouldRunExpandedWaterSearch(primaryResult, consumeRadius);
    }

    static boolean shouldCacheRejectorWaterSearchResultForTests(@Nonnull WaterTargetSearchResult result) {
        return shouldCacheRejectorWaterSearchResult(result);
    }

    static boolean waterStandTargetsIncludeSourceBlockForTests() {
        return WATER_STAND_TARGETS_INCLUDE_SOURCE_BLOCK;
    }

    @Nonnull
    static Vector3d cacheSearchTargetRoundTripForTests(@Nonnull Vector3d target, long nowMs) {
        SEARCH_CACHE.clear();
        NeedsSearchCacheKey cacheKey = new NeedsSearchCacheKey(
                new UUID(0L, 1L),
                "test-world",
                ResourceSearchKind.FOOD_CONTAINER,
                1,
                2,
                3,
                4,
                5,
                0,
                6
        );
        cacheSearchTarget(cacheKey, target, nowMs);
        Vector3d cachedTarget = getCachedSearchTarget(cacheKey, nowMs);
        return cachedTarget == CACHE_MISS ? new Vector3d(Double.NaN, Double.NaN, Double.NaN) : cachedTarget;
    }

    @Nonnull
    static FoodTargetSearchResult cacheFoodSearchResultRoundTripForTests(@Nonnull FoodTargetSearchResult result,
                                                                         long nowMs) {
        SEARCH_CACHE.clear();
        NeedsSearchCacheKey cacheKey = new NeedsSearchCacheKey(
                new UUID(0L, 2L),
                "test-world",
                ResourceSearchKind.FOOD_CONTAINER,
                1,
                2,
                3,
                4,
                5,
                7,
                6
        );
        cacheFoodSearchResult(cacheKey, result, nowMs);
        FoodTargetSearchResult cachedResult = getCachedFoodSearchResult(
                cacheKey,
                result.target() != null ? result.target() : new Vector3d(1.0, 2.0, 3.0),
                16.0,
                16,
                nowMs
        );
        return cachedResult != null ? cachedResult : FoodTargetSearchResult.miss(false);
    }

    private static void recordResourceSearchWork(long startedNs, long nowMs) {
        long elapsedNs = Math.max(0L, System.nanoTime() - startedNs);
        TameworkRuntimePressureService.getInstance().recordWork(
                RuntimePressureDomain.NEEDS_RESOURCE_SEARCH,
                elapsedNs,
                nowMs
        );
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
                                                            double consumeRadius,
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
        int blockX = NeedsResourceSearchCachePolicy.quantizedBlockCoordinate((int) Math.floor(position.x));
        int blockY = NeedsResourceSearchCachePolicy.quantizedBlockCoordinate((int) Math.floor(position.y));
        int blockZ = NeedsResourceSearchCachePolicy.quantizedBlockCoordinate((int) Math.floor(position.z));
        int radiusKey = Math.max(1, (int) Math.ceil(radius * 10.0));
        int consumeRadiusKey = Math.max(0, (int) Math.ceil(consumeRadius * 10.0));
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
                consumeRadiusKey,
                itemIdsHash
        );
    }

    private static long resolveCurrentTimeMs() {
        return System.currentTimeMillis();
    }

    private static boolean shouldRunExpandedWaterSearch(@Nonnull WaterTargetSearchResult primaryResult,
                                                        double consumeRadius) {
        return primaryResult.target() == null
                && primaryResult.foundConsumableSource()
                && consumeRadius > 1.0 + SCORE_EPSILON;
    }

    private static boolean shouldCacheRejectorWaterSearchResult(@Nonnull WaterTargetSearchResult result) {
        return result.target() == null && !result.foundConsumableSource();
    }

    private static boolean shouldCacheRejectorFoodSearchResult(@Nonnull FoodTargetSearchResult result) {
        return result.target() == null && !result.foundConsumableSource();
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
                                        int consumeRadiusKey,
                                        int itemIdsHash) {
    }

    private record CachedSearchResult(@Nullable Vector3d target,
                                      boolean foundConsumableSource,
                                      boolean foundConsumableSourceInConsumeRange,
                                      double approachRadius,
                                      long expiresAtMs) {
    }

    public record WaterTargetSearchResult(@Nullable Vector3d target,
                                          boolean foundConsumableSource,
                                          boolean foundConsumableSourceInConsumeRange,
                                          double approachRadius) {
        public WaterTargetSearchResult(@Nullable Vector3d target,
                                       boolean foundConsumableSource,
                                       boolean foundConsumableSourceInConsumeRange) {
            this(target, foundConsumableSource, foundConsumableSourceInConsumeRange, DEFAULT_APPROACH_RADIUS);
        }

        @Nonnull
        public static WaterTargetSearchResult target(@Nonnull Vector3d target) {
            return target(target, DEFAULT_APPROACH_RADIUS);
        }

        @Nonnull
        public static WaterTargetSearchResult target(@Nonnull Vector3d target, double approachRadius) {
            return target(target, true, approachRadius);
        }

        @Nonnull
        private static WaterTargetSearchResult target(@Nonnull Vector3d target,
                                                      boolean foundConsumableSourceInConsumeRange,
                                                      double approachRadius) {
            return new WaterTargetSearchResult(
                    target,
                    true,
                    foundConsumableSourceInConsumeRange,
                    sanitizeApproachRadius(approachRadius)
            );
        }

        @Nonnull
        public static WaterTargetSearchResult miss(boolean foundConsumableSource) {
            return miss(foundConsumableSource, false);
        }

        @Nonnull
        private static WaterTargetSearchResult miss(boolean foundConsumableSource,
                                                   boolean foundConsumableSourceInConsumeRange) {
            return new WaterTargetSearchResult(
                    null,
                    foundConsumableSource,
                    foundConsumableSourceInConsumeRange,
                    DEFAULT_APPROACH_RADIUS
            );
        }

    }

    public record FoodTargetSearchResult(@Nullable Vector3d target,
                                         boolean foundConsumableSource,
                                         boolean foundConsumableSourceInConsumeRange,
                                         double approachRadius) {
        public FoodTargetSearchResult(@Nullable Vector3d target,
                                      boolean foundConsumableSource,
                                      boolean foundConsumableSourceInConsumeRange) {
            this(target, foundConsumableSource, foundConsumableSourceInConsumeRange, DEFAULT_APPROACH_RADIUS);
        }

        @Nonnull
        public static FoodTargetSearchResult target(@Nonnull Vector3d target) {
            return target(target, DEFAULT_APPROACH_RADIUS);
        }

        @Nonnull
        public static FoodTargetSearchResult target(@Nonnull Vector3d target, double approachRadius) {
            return target(target, true, approachRadius);
        }

        @Nonnull
        private static FoodTargetSearchResult target(@Nonnull Vector3d target,
                                                     boolean foundConsumableSourceInConsumeRange,
                                                     double approachRadius) {
            return new FoodTargetSearchResult(
                    target,
                    true,
                    foundConsumableSourceInConsumeRange,
                    sanitizeApproachRadius(approachRadius)
            );
        }

        @Nonnull
        public static FoodTargetSearchResult miss(boolean foundConsumableSource) {
            return miss(foundConsumableSource, false);
        }

        @Nonnull
        private static FoodTargetSearchResult miss(boolean foundConsumableSource,
                                                  boolean foundConsumableSourceInConsumeRange) {
            return new FoodTargetSearchResult(
                    null,
                    foundConsumableSource,
                    foundConsumableSourceInConsumeRange,
                    DEFAULT_APPROACH_RADIUS
            );
        }
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
    ContainerConsumeResult consumeContainerFoodAtDetailed(@Nullable Ref<EntityStore> npcRef,
                                                          @Nullable Store<EntityStore> store,
                                                          @Nonnull TwNeedsConfig config,
                                                          @Nullable String roleId,
                                                          @Nullable String[] preferredFoodItemIds,
                                                          @Nullable Vector3d consumeOrigin) {
        TwNeedsConfig.PassiveRefillSettings passiveRefill = config.getPassiveRefill();
        int maxItems = passiveRefill.getMaxContainerItemsConsumedPerSweep();
        int verticalScanRadius = passiveRefill.getContainerVerticalScanRadius();
        double radius = passiveRefill.getContainerConsumeRadius();
        int blockX = isFinitePosition(consumeOrigin) ? finiteBlockCoordinate(consumeOrigin.x) : 0;
        int blockY = isFinitePosition(consumeOrigin) ? finiteBlockCoordinate(consumeOrigin.y) : 0;
        int blockZ = isFinitePosition(consumeOrigin) ? finiteBlockCoordinate(consumeOrigin.z) : 0;
        int npcBlockX = blockX;
        int npcBlockY = blockY;
        int npcBlockZ = blockZ;
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return new ContainerConsumeResult(
                    0, 0, 0, 0, 0, 0, maxItems, radius, verticalScanRadius,
                    blockX, blockY, blockZ,
                    npcBlockX, npcBlockY, npcBlockZ,
                    true,
                    Double.NaN, Double.NaN,
                    ContainerConsumeStatus.INVALID_CONTEXT
            );
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        if (transform != null && transform.getPosition() != null && isFinitePosition(transform.getPosition())) {
            Vector3d npcPosition = transform.getPosition();
            npcBlockX = finiteBlockCoordinate(npcPosition.x);
            npcBlockY = finiteBlockCoordinate(npcPosition.y);
            npcBlockZ = finiteBlockCoordinate(npcPosition.z);
        }
        if (maxItems <= 0) {
            return new ContainerConsumeResult(
                    0, 0, 0, 0, 0, 0, maxItems, radius, verticalScanRadius,
                    blockX, blockY, blockZ,
                    npcBlockX, npcBlockY, npcBlockZ,
                    true,
                    Double.NaN, Double.NaN,
                    ContainerConsumeStatus.MAX_ITEMS_NON_POSITIVE
            );
        }
        Set<String> allowedFoods = normalizeItemIds(preferredFoodItemIds);
        allowedFoods.addAll(normalizeItemIds(TwFoodConfig.resolveNeedsConsumeItemIdsForRole(roleId)));
        allowedFoods.addAll(normalizeItemIds(passiveRefill.getContainerFoodItemIds()));
        if (allowedFoods.isEmpty()) {
            return new ContainerConsumeResult(
                    0, 0, 0, 0, 0, 0, maxItems, radius, verticalScanRadius,
                    blockX, blockY, blockZ,
                    npcBlockX, npcBlockY, npcBlockZ,
                    true,
                    Double.NaN, Double.NaN,
                    ContainerConsumeStatus.ALLOWED_FOODS_EMPTY
            );
        }
        TargetBlock target = resolveTargetBlock(store, consumeOrigin);
        if (target == null) {
            return new ContainerConsumeResult(
                    0, 0, 0, 0, 0, 0, maxItems, radius, verticalScanRadius,
                    blockX, blockY, blockZ,
                    npcBlockX, npcBlockY, npcBlockZ,
                    true,
                    Double.NaN, Double.NaN,
                    ContainerConsumeStatus.WORLD_CONTEXT_MISSING
            );
        }
        Object containerState = FeedTroughContainerCompat.resolveContainerState(
                target.worldChunk(),
                target.chunkStoreStore(),
                target.x(),
                target.y(),
                target.z()
        );
        ItemContainer container = FeedTroughContainerCompat.getItemContainer(containerState);
        if (container == null) {
            return new ContainerConsumeResult(
                    0, 0, 0, 0, 0, 0, maxItems, radius, verticalScanRadius,
                    target.x(), target.y(), target.z(),
                    npcBlockX, npcBlockY, npcBlockZ,
                    true,
                    Double.NaN, Double.NaN,
                    ContainerConsumeStatus.NO_CONTAINER_IN_RANGE
            );
        }
        if (!containsAllowedFood(container, allowedFoods)) {
            return new ContainerConsumeResult(
                    0, 0, 1, 0, 0, 0, maxItems, radius, verticalScanRadius,
                    target.x(), target.y(), target.z(),
                    npcBlockX, npcBlockY, npcBlockZ,
                    true,
                    0.0, Double.NaN,
                    ContainerConsumeStatus.NO_ALLOWED_FOOD_IN_RANGE
            );
        }
        FeedItemPreferenceResolver preferenceResolver = FeedItemPreferenceResolver.create(npcRef, store, roleId);
        SlotConsumeResult slotResult = consumeFoodFromContainerDetailed(
                container,
                allowedFoods,
                maxItems,
                preferenceResolver
        );
        ContainerConsumeStatus status;
        if (slotResult.consumed > 0) {
            status = ContainerConsumeStatus.SUCCESS;
        } else if (slotResult.removalAttempts > 0 && slotResult.removalFailures >= slotResult.removalAttempts) {
            status = ContainerConsumeStatus.REMOVE_TRANSACTION_FAILED;
        } else {
            status = ContainerConsumeStatus.NO_ITEMS_CONSUMED;
        }
        return new ContainerConsumeResult(
                slotResult.consumed,
                slotResult.consumedItemCountsByItemId,
                1,
                1,
                slotResult.matchingStacksSeen,
                slotResult.removalAttempts,
                slotResult.removalFailures,
                maxItems,
                radius,
                verticalScanRadius,
                target.x(),
                target.y(),
                target.z(),
                npcBlockX,
                npcBlockY,
                npcBlockZ,
                true,
                0.0,
                0.0,
                status
        );
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
        allowedFoods.addAll(normalizeItemIds(TwFoodConfig.resolveNeedsConsumeItemIdsForRole(roleId)));
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
    private static TargetBlock resolveTargetBlock(@Nullable Store<EntityStore> store,
                                                  @Nullable Vector3d consumeOrigin) {
        if (store == null || !isFinitePosition(consumeOrigin)) {
            return null;
        }
        World world = resolveWorld(store);
        if (world == null || world.getChunkStore() == null) {
            return null;
        }
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunkStoreStore = chunkStore.getStore();
        if (chunkStoreStore == null) {
            return null;
        }
        int blockX = finiteBlockCoordinate(consumeOrigin.x);
        int blockY = finiteBlockCoordinate(consumeOrigin.y);
        int blockZ = finiteBlockCoordinate(consumeOrigin.z);
        Map<Long, WorldChunk> chunkCache = new HashMap<>();
        WorldChunk worldChunk = resolveWorldChunk(chunkStore, chunkStoreStore, blockX, blockZ, chunkCache);
        if (worldChunk == null) {
            return null;
        }
        return new TargetBlock(worldChunk, chunkStoreStore, blockX, blockY, blockZ);
    }

    private static boolean isFinitePosition(@Nullable Vector3d position) {
        return position != null
                && Double.isFinite(position.x)
                && Double.isFinite(position.y)
                && Double.isFinite(position.z);
    }

    private static int finiteBlockCoordinate(double value) {
        return (int) Math.floor(value);
    }

    @Nonnull
    private static Vector3d sourceBlockTarget(int blockX, int blockY, int blockZ) {
        return new Vector3d(
                blockX + SOURCE_TARGET_CENTER_OFFSET,
                blockY + SOURCE_TARGET_CENTER_OFFSET,
                blockZ + SOURCE_TARGET_CENTER_OFFSET
        );
    }

    private static double sanitizeApproachRadius(double approachRadius) {
        return Double.isFinite(approachRadius) && approachRadius > SCORE_EPSILON
                ? approachRadius
                : DEFAULT_APPROACH_RADIUS;
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
        int blockId = worldChunk.getBlock(blockX, blockY, blockZ);
        if (!isWaterTroughBlockId(blockId)) {
            return false;
        }
        BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
        return FeedTroughWaterStateService.hasConsumableWater(
                worldChunk,
                chunkStore,
                blockX,
                blockY,
                blockZ,
                blockType
        );
    }

    private static boolean isWaterTroughBlockId(int blockId) {
        if (blockId == 0) {
            return false;
        }
        return WATER_TROUGH_BLOCK_ID_CACHE.computeIfAbsent(
                blockId,
                CompanionNeedsEnvironmentService::resolveWaterTroughBlockId
        );
    }

    private static boolean resolveWaterTroughBlockId(int blockId) {
        BlockType blockType = BlockType.getAssetMap().getAsset(blockId);
        return FeedTroughWaterStateService.isWaterTroughBlockType(blockType);
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
