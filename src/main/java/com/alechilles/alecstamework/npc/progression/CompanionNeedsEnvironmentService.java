package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
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
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Handles world-environment probes for needs progression (nearby water and nearby container food).
 */
public final class CompanionNeedsEnvironmentService {
    private static final int CONTAINER_VERTICAL_SCAN_RADIUS = 2;
    private static final int WATER_VERTICAL_SCAN_RADIUS = 1;
    private static final int[][] HORIZONTAL_NEIGHBOR_OFFSETS = {
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1}
    };
    private static final int[] STAND_HEIGHT_OFFSETS = {0, 1, -1};
    private static final double STAND_POSITION_Y_OFFSET = 0.05;

    boolean isNearWater(@Nullable Ref<EntityStore> npcRef,
                        @Nullable Store<EntityStore> store,
                        @Nonnull TwNeedsConfig config) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        World world = resolveWorld(store);
        if (transform == null || world == null || world.getChunkStore() == null) {
            return false;
        }
        double radius = config.getPassiveRefill().getWaterSearchRadius();
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return false;
        }
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunkStoreStore = chunkStore.getStore();
        if (chunkStoreStore == null) {
            return false;
        }
        int blockX = (int) Math.floor(transform.getPosition().x);
        int blockY = (int) Math.floor(transform.getPosition().y);
        int blockZ = (int) Math.floor(transform.getPosition().z);
        int searchRadius = Math.max(1, (int) Math.ceil(radius));
        double radiusSq = radius * radius;
        Map<Long, WorldChunk> chunkCache = new HashMap<>();
        for (int y = blockY - WATER_VERTICAL_SCAN_RADIUS; y <= blockY + WATER_VERTICAL_SCAN_RADIUS; y++) {
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
                    if (worldChunk.getFluidId(x, y, z) != 0) {
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
        return findNearestWaterDrinkingPosition(
                npcRef,
                store,
                config.getPassiveRefill().getWaterSearchRadius()
        );
    }

    @Nullable
    public Vector3d findNearestWaterDrinkingPosition(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store,
                                                     double radius) {
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
        Map<Long, WorldChunk> chunkCache = new HashMap<>();
        Vector3d bestTarget = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (int y = blockY - WATER_VERTICAL_SCAN_RADIUS; y <= blockY + WATER_VERTICAL_SCAN_RADIUS; y++) {
            for (int x = blockX - searchRadius; x <= blockX + searchRadius; x++) {
                for (int z = blockZ - searchRadius; z <= blockZ + searchRadius; z++) {
                    double dx = x - blockX;
                    double dz = z - blockZ;
                    if ((dx * dx) + (dz * dz) > radiusSq) {
                        continue;
                    }
                    WorldChunk worldChunk = resolveWorldChunk(chunkStore, chunkStoreStore, x, z, chunkCache);
                    if (worldChunk == null || worldChunk.getFluidId(x, y, z) == 0) {
                        continue;
                    }
                    Vector3d standPosition = findNearestStandPositionAdjacentToBlock(
                            chunkStore,
                            chunkStoreStore,
                            x,
                            y,
                            z,
                            transform.getPosition(),
                            chunkCache
                    );
                    if (standPosition == null) {
                        continue;
                    }
                    double distanceSq = distanceSquared(standPosition, transform.getPosition());
                    if (!Double.isFinite(distanceSq) || distanceSq >= bestDistanceSq) {
                        continue;
                    }
                    bestDistanceSq = distanceSq;
                    bestTarget = standPosition;
                }
            }
        }
        return bestTarget;
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
                passive.getContainerFoodItemIds()
        );
    }

    @Nullable
    public Vector3d findNearestFoodContainerPosition(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store,
                                                     double radius,
                                                     @Nullable String[] allowedItemIds) {
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
        Map<Long, WorldChunk> chunkCache = new HashMap<>();
        Vector3d bestTarget = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (int y = blockY - CONTAINER_VERTICAL_SCAN_RADIUS; y <= blockY + CONTAINER_VERTICAL_SCAN_RADIUS; y++) {
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
                    BlockState state = worldChunk.getState(x, y, z);
                    if (!(state instanceof ItemContainerState containerState)) {
                        continue;
                    }
                    if (!containsAllowedFood(containerState.getItemContainer(), allowedFoods)) {
                        continue;
                    }
                    Vector3d standPosition = findNearestStandPositionAdjacentToBlock(
                            chunkStore,
                            chunkStoreStore,
                            x,
                            y,
                            z,
                            transform.getPosition(),
                            chunkCache
                    );
                    if (standPosition == null) {
                        continue;
                    }
                    double distanceSq = distanceSquared(standPosition, transform.getPosition());
                    if (!Double.isFinite(distanceSq) || distanceSq >= bestDistanceSq) {
                        continue;
                    }
                    bestDistanceSq = distanceSq;
                    bestTarget = standPosition;
                }
            }
        }
        return bestTarget;
    }

    int consumeNearbyContainerFood(@Nullable Ref<EntityStore> npcRef,
                                   @Nullable Store<EntityStore> store,
                                   @Nonnull TwNeedsConfig config) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return 0;
        }
        TwNeedsConfig.PassiveRefillSettings passiveRefill = config.getPassiveRefill();
        int maxItems = passiveRefill.getMaxContainerItemsConsumedPerSweep();
        if (maxItems <= 0) {
            return 0;
        }
        Set<String> allowedFoods = normalizeItemIds(passiveRefill.getContainerFoodItemIds());
        if (allowedFoods.isEmpty()) {
            return 0;
        }
        TransformComponent transform = store.getComponent(npcRef, TransformComponent.getComponentType());
        World world = resolveWorld(store);
        if (transform == null || world == null || world.getChunkStore() == null) {
            return 0;
        }
        double radius = passiveRefill.getContainerSearchRadius();
        if (!Double.isFinite(radius) || radius <= 0.0) {
            return 0;
        }
        ChunkStore chunkStore = world.getChunkStore();
        Store<ChunkStore> chunkStoreStore = chunkStore.getStore();
        if (chunkStoreStore == null) {
            return 0;
        }
        int consumed = 0;
        int blockX = (int) Math.floor(transform.getPosition().x);
        int blockY = (int) Math.floor(transform.getPosition().y);
        int blockZ = (int) Math.floor(transform.getPosition().z);
        int searchRadius = Math.max(1, (int) Math.ceil(radius));
        double radiusSq = radius * radius;
        Map<Long, WorldChunk> chunkCache = new HashMap<>();
        for (int y = blockY - CONTAINER_VERTICAL_SCAN_RADIUS; y <= blockY + CONTAINER_VERTICAL_SCAN_RADIUS; y++) {
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
                    BlockState state = worldChunk.getState(x, y, z);
                    if (!(state instanceof ItemContainerState containerState)) {
                        continue;
                    }
                    consumed += consumeFoodFromContainer(
                            containerState.getItemContainer(),
                            allowedFoods,
                            maxItems - consumed
                    );
                    if (consumed >= maxItems) {
                        return consumed;
                    }
                }
            }
        }
        return consumed;
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
        Vector3d bestTarget = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (int[] offset : HORIZONTAL_NEIGHBOR_OFFSETS) {
            int candidateX = sourceX + offset[0];
            int candidateZ = sourceZ + offset[1];
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
        if (container == null || maxItems <= 0 || allowedFoods.isEmpty()) {
            return 0;
        }
        int consumed = 0;
        short capacity = container.getCapacity();
        for (short slot = 0; slot < capacity && consumed < maxItems; slot++) {
            ItemStack stack = container.getItemStack(slot);
            if (ItemStack.isEmpty(stack)) {
                continue;
            }
            String itemId = stack.getItemId();
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            if (!allowedFoods.contains(itemId.trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            while (consumed < maxItems) {
                ItemStackSlotTransaction transaction = container.removeItemStackFromSlot(slot, 1, false, true);
                if (transaction == null || !transaction.succeeded()) {
                    break;
                }
                consumed++;
                ItemStack remaining = container.getItemStack(slot);
                if (ItemStack.isEmpty(remaining)) {
                    break;
                }
            }
        }
        return consumed;
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

    private static double distanceSquared(@Nonnull Vector3d left, @Nonnull Vector3d right) {
        double dx = left.x - right.x;
        double dy = left.y - right.y;
        double dz = left.z - right.z;
        return (dx * dx) + (dy * dy) + (dz * dz);
    }
}
