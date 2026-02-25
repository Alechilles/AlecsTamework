package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
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
final class CompanionNeedsEnvironmentService {
    private static final int CONTAINER_VERTICAL_SCAN_RADIUS = 2;
    private static final int WATER_VERTICAL_SCAN_RADIUS = 1;

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
}
