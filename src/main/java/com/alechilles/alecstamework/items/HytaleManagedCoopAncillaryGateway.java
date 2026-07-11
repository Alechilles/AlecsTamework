package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopAncillaryBehavior.BlockAccess;
import com.alechilles.alecstamework.items.ManagedCoopAncillaryBehavior.InventoryApply;
import com.alechilles.alecstamework.items.ManagedCoopAncillaryBehavior.RuntimeGateway;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.hypixel.hytale.builtin.adventure.farming.config.FarmingCoopAsset;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDrop;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemDropList;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ListTransaction;
import com.hypixel.hytale.server.core.modules.block.BlockModule.BlockStateInfo;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * Hytale block/container and drop-list bridge for managed-coop ancillary behavior.
 *
 * <p>Base-game block identity, inventory, drop generation, and interaction-state primitives are
 * reused here. Resident admission, occupancy, deployment, and vanilla coop mutation are outside
 * this bridge.</p>
 */
final class HytaleManagedCoopAncillaryGateway implements RuntimeGateway {
    private final ManagedCoopAuthorityResolver authorityResolver;

    HytaleManagedCoopAncillaryGateway() {
        this(new ManagedCoopAuthorityResolver());
    }

    HytaleManagedCoopAncillaryGateway(@Nonnull ManagedCoopAuthorityResolver authorityResolver) {
        this.authorityResolver = Objects.requireNonNull(authorityResolver, "authorityResolver");
    }

    @Override
    public boolean enqueue(@Nonnull String worldName, @Nonnull Runnable task) {
        Objects.requireNonNull(task, "task");
        World world = resolveWorld(worldName);
        if (!matchesWorld(world, worldName)) {
            return false;
        }
        try {
            world.execute(task);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** Resolves the exact typed block component and its current container on the world thread. */
    @Nullable
    @Override
    public BlockAccess resolve(@Nonnull ManagedCoopAncillaryRequest request) {
        Objects.requireNonNull(request, "request");
        ManagedCoopAuthorityKey authorityKey = request.authorityKey();
        World world = resolveWorld(authorityKey.worldName());
        if (!matchesWorld(world, authorityKey.worldName()) || world.getChunkStore() == null) {
            return null;
        }
        Store<ChunkStore> store = world.getChunkStore().getStore();
        if (store == null) {
            return null;
        }
        store.assertThread();
        WorldChunk chunk = loadedChunk(world, authorityKey);
        return chunk == null ? null : typedBlock(store, chunk, request);
    }

    @Nullable
    private WorldChunk loadedChunk(World world, ManagedCoopAuthorityKey authorityKey) {
        WorldChunk chunk = world.getChunkIfInMemory(
                ChunkUtil.indexChunkFromBlock(authorityKey.x(), authorityKey.z()));
        return chunk != null && chunk.getWorld() == world ? chunk : null;
    }

    @Nullable
    private BlockAccess typedBlock(Store<ChunkStore> store,
                                   WorldChunk chunk,
                                   ManagedCoopAncillaryRequest request) {
        ManagedCoopAuthorityKey authorityKey = request.authorityKey();
        Ref<ChunkStore> blockRef = chunk.getBlockComponentEntity(
                authorityKey.x(), authorityKey.y(), authorityKey.z());
        ComponentType<ChunkStore, ItemContainerBlock> containerType =
                ItemContainerBlock.getComponentType();
        ComponentType<ChunkStore, CoopBlock> coopType = CoopBlock.getComponentType();
        ComponentType<ChunkStore, BlockStateInfo> infoType = BlockStateInfo.getComponentType();
        if (blockRef == null || !blockRef.isValid() || containerType == null
                || coopType == null || infoType == null) {
            return null;
        }
        ItemContainerBlock containerBlock = store.getComponent(blockRef, containerType);
        CoopBlock coopBlock = store.getComponent(blockRef, coopType);
        BlockStateInfo blockInfo = store.getComponent(blockRef, infoType);
        ItemContainer container = containerBlock != null ? containerBlock.getItemContainer() : null;
        FarmingCoopAsset coopAsset = coopBlock != null ? coopBlock.getCoopAsset() : null;
        BlockType blockType = chunk.getBlockType(
                authorityKey.x(), authorityKey.y(), authorityKey.z());
        if (container == null || coopAsset == null || blockType == null
                || !matchesCoopAsset(coopAsset.getId(), request.coopId())
                || !matchesBlockInfo(store, chunk, blockInfo, authorityKey)) {
            return null;
        }
        ManagedCoopContext current = authorityResolver.resolve(
                authorityKey.worldName(),
                blockType.getId(),
                coopAsset.getId(),
                new Vector3i(authorityKey.x(), authorityKey.y(), authorityKey.z()),
                chunk.getRotationIndex(authorityKey.x(), authorityKey.y(), authorityKey.z()),
                container);
        if (current == null || !current.config().isManagedAuthorityEnabled()
                || !current.matchesExact(
                        authorityKey.worldName(), request.coopId(),
                        authorityKey.x(), authorityKey.y(), authorityKey.z())) {
            return null;
        }
        return new LiveBlock(
                chunk, blockType,
                new Vector3i(authorityKey.x(), authorityKey.y(), authorityKey.z()),
                container);
    }

    private boolean matchesCoopAsset(@Nullable String assetId, String expectedCoopId) {
        String normalizedAsset = normalize(assetId);
        String normalizedExpected = normalize(expectedCoopId);
        if (normalizedAsset.equals(normalizedExpected)) {
            return true;
        }
        int separator = Math.max(
                normalizedAsset.lastIndexOf('/'),
                Math.max(normalizedAsset.lastIndexOf(':'), normalizedAsset.lastIndexOf('.')));
        return separator >= 0 && separator + 1 < normalizedAsset.length()
                && normalizedAsset.substring(separator + 1).equals(normalizedExpected);
    }

    private boolean matchesBlockInfo(Store<ChunkStore> store,
                                     WorldChunk expectedChunk,
                                     @Nullable BlockStateInfo info,
                                     ManagedCoopAuthorityKey authorityKey) {
        Ref<ChunkStore> chunkRef = info != null ? info.getChunkRef() : null;
        if (chunkRef == null || !chunkRef.isValid()) {
            return false;
        }
        WorldChunk stateChunk = store.getComponent(chunkRef, WorldChunk.getComponentType());
        if (stateChunk == null || stateChunk.getWorld() != expectedChunk.getWorld()) {
            return false;
        }
        int blockIndex = info.getIndex();
        int worldX = ChunkUtil.worldCoordFromLocalCoord(
                stateChunk.getX(), ChunkUtil.xFromBlockInColumn(blockIndex));
        int worldY = ChunkUtil.yFromBlockInColumn(blockIndex);
        int worldZ = ChunkUtil.worldCoordFromLocalCoord(
                stateChunk.getZ(), ChunkUtil.zFromBlockInColumn(blockIndex));
        return worldX == authorityKey.x()
                && worldY == authorityKey.y()
                && worldZ == authorityKey.z();
    }

    @Override
    public InventoryApply addOne(@Nonnull BlockAccess block, @Nonnull String dropReferenceId) {
        if (!(block instanceof LiveBlock live)
                || dropReferenceId == null || dropReferenceId.isBlank()) {
            return InventoryApply.possiblyPartial("managed_coop_produce_invalid_inventory_request");
        }
        try {
            List<ItemStack> generated = generatedStacks(dropReferenceId);
            if (generated.isEmpty()) {
                return InventoryApply.applied();
            }
            if (!live.container().canAddItemStacks(generated)) {
                return InventoryApply.saturated();
            }
            ListTransaction<ItemStackTransaction> transaction =
                    live.container().addItemStacks(generated, true, false, true);
            if (!fullyApplied(transaction)) {
                return InventoryApply.possiblyPartial(
                        "managed_coop_produce_atomic_transaction_rejected");
            }
            return InventoryApply.applied();
        } catch (RuntimeException exception) {
            // An inventory API exception does not prove that no slot changed. Consume cadence.
            return InventoryApply.possiblyPartial(failureDetail(exception));
        }
    }

    @Nonnull
    private List<ItemStack> generatedStacks(String dropReferenceId) {
        ItemDropList dropList = resolveDropList(dropReferenceId);
        if (dropList == null || dropList.getContainer() == null) {
            return List.of(new ItemStack(dropReferenceId, 1));
        }
        ArrayList<ItemDrop> generated = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        dropList.getContainer().populateDrops(generated, random::nextDouble, dropReferenceId);
        ArrayList<ItemStack> stacks = new ArrayList<>(generated.size());
        for (ItemDrop drop : generated) {
            if (drop == null || drop.getItemId() == null || drop.getItemId().isBlank()) {
                continue;
            }
            int quantity = drop.getRandomQuantity(random);
            if (quantity > 0) {
                stacks.add(new ItemStack(drop.getItemId(), quantity, drop.getMetadata()));
            }
        }
        return List.copyOf(stacks);
    }

    private boolean fullyApplied(@Nullable ListTransaction<ItemStackTransaction> transaction) {
        if (transaction == null || !transaction.succeeded() || transaction.getList() == null) {
            return false;
        }
        for (ItemStackTransaction item : transaction.getList()) {
            ItemStack remainder = item != null ? item.getRemainder() : null;
            if (item == null || !item.succeeded()
                    || remainder != null && !remainder.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private ItemDropList resolveDropList(String dropReferenceId) {
        DefaultAssetMap<String, ItemDropList> assets = ItemDropList.getAssetMap();
        if (assets == null) {
            return null;
        }
        ItemDropList direct = assets.getAsset(dropReferenceId);
        if (direct != null) {
            return direct;
        }
        Map<String, ItemDropList> assetMap = assets.getAssetMap();
        if (assetMap == null || assetMap.isEmpty()) {
            return null;
        }
        direct = assetMap.get(dropReferenceId);
        if (direct != null) {
            return direct;
        }
        String normalized = normalize(dropReferenceId);
        for (Map.Entry<String, ItemDropList> entry : assetMap.entrySet()) {
            if (normalized.equals(normalize(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Nullable
    private World resolveWorld(@Nullable String worldName) {
        Universe universe = Universe.get();
        return universe == null || worldName == null || worldName.isBlank()
                ? null : universe.getWorld(worldName);
    }

    private boolean matchesWorld(@Nullable World world, @Nullable String worldName) {
        return world != null && world.getName() != null && worldName != null
                && world.getName().equalsIgnoreCase(worldName);
    }

    private String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String failureDetail(RuntimeException exception) {
        String message = exception.getMessage();
        return "managed_coop_produce_inventory_exception:"
                + (message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message);
    }

    private record LiveBlock(@Nonnull WorldChunk chunk,
                             @Nonnull BlockType blockType,
                             @Nonnull Vector3i block,
                             @Nonnull ItemContainer container) implements BlockAccess {
        private LiveBlock {
            Objects.requireNonNull(chunk, "chunk");
            Objects.requireNonNull(blockType, "blockType");
            block = new Vector3i(Objects.requireNonNull(block, "block"));
            Objects.requireNonNull(container, "container");
        }

        @Override
        public boolean isEmpty() {
            return container.isEmpty();
        }

        @Override
        public void setInteractionState(@Nonnull String state) {
            chunk.setBlockInteractionState(block, blockType, state);
        }
    }
}
