package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.hypixel.hytale.builtin.adventure.farming.config.FarmingCoopAsset;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule.BlockStateInfo;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/** Reads exact loaded-block evidence without inspecting or mutating vanilla resident occupancy. */
final class HytaleManagedCoopRemovalEvidenceReader {
    private final ManagedCoopAuthorityResolver authorityResolver;

    HytaleManagedCoopRemovalEvidenceReader() {
        this(new ManagedCoopAuthorityResolver());
    }

    HytaleManagedCoopRemovalEvidenceReader(
            @Nonnull ManagedCoopAuthorityResolver authorityResolver) {
        this.authorityResolver = Objects.requireNonNull(authorityResolver, "authorityResolver");
    }

    @Nonnull
    ManagedCoopRemovalEvidence.Result inspect(
            @Nonnull Store<ChunkStore> store,
            @Nonnull World world,
            @Nonnull ManagedCoopAuthorityKey key,
            @Nonnull String expectedCoopId) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(key, "key");
        store.assertThread();
        if (world.getName() == null || !world.getName().equalsIgnoreCase(key.worldName())) {
            return deferred("coop_world_identity_mismatch");
        }
        WorldChunk chunk = world.getChunkIfInMemory(
                ChunkUtil.indexChunkFromBlock(key.x(), key.z()));
        if (chunk == null || chunk.getWorld() != world) {
            return ManagedCoopRemovalEvidence.classify(
                    false, false, false, false, false, false,
                    false, false, false, false, false, false, 0);
        }

        BlockType blockType = chunk.getBlockType(key.x(), key.y(), key.z());
        int rotation = chunk.getRotationIndex(key.x(), key.y(), key.z());
        ComponentType<ChunkStore, CoopBlock> coopType = CoopBlock.getComponentType();
        ComponentType<ChunkStore, BlockStateInfo> infoType = BlockStateInfo.getComponentType();
        boolean componentTypesAvailable = coopType != null && infoType != null;
        BlockTypeMatch blockTypeMatch = matchesBlockType(
                blockType != null ? blockType.getId() : null, expectedCoopId);
        Ref<ChunkStore> blockRef = chunk.getBlockComponentEntity(key.x(), key.y(), key.z());
        boolean referencePresent = blockRef != null;
        boolean referenceValid = referencePresent && blockRef.isValid();

        BlockStateInfo info = referenceValid && infoType != null
                ? store.getComponent(blockRef, infoType) : null;
        boolean exactIdentity = referenceValid && matchesBlockInfo(store, world, info, key);
        CoopBlock coop = exactIdentity && coopType != null
                ? store.getComponent(blockRef, coopType) : null;
        FarmingCoopAsset asset = coop != null ? coop.getCoopAsset() : null;
        boolean matchingAsset = asset != null
                && matchesIdentifier(asset.getId(), expectedCoopId);
        boolean exactManaged = matchingAsset && exactManagedContext(
                store, blockRef, chunk, blockType, asset, key, expectedCoopId, rotation);

        return ManagedCoopRemovalEvidence.classify(
                true,
                blockType != null,
                componentTypesAvailable,
                referencePresent,
                referenceValid,
                exactIdentity,
                coop != null,
                asset != null,
                blockTypeMatch.available(),
                blockTypeMatch.matching(),
                matchingAsset,
                exactManaged,
                rotation);
    }

    private boolean exactManagedContext(Store<ChunkStore> store,
                                        Ref<ChunkStore> blockRef,
                                        WorldChunk chunk,
                                        BlockType blockType,
                                        FarmingCoopAsset asset,
                                        ManagedCoopAuthorityKey key,
                                        String expectedCoopId,
                                        int rotation) {
        ComponentType<ChunkStore, ItemContainerBlock> containerType =
                ItemContainerBlock.getComponentType();
        ItemContainerBlock containerBlock = containerType != null
                ? store.getComponent(blockRef, containerType) : null;
        ItemContainer container = containerBlock != null
                ? containerBlock.getItemContainer() : null;
        ManagedCoopContext current = authorityResolver.resolve(
                key.worldName(), blockType.getId(), asset.getId(),
                new Vector3i(key.x(), key.y(), key.z()), rotation, container);
        return current != null && current.matchesExact(
                key.worldName(), expectedCoopId, key.x(), key.y(), key.z())
                && chunk.getWorld() != null;
    }

    private boolean matchesBlockInfo(Store<ChunkStore> store,
                                     World expectedWorld,
                                     @Nullable BlockStateInfo info,
                                     ManagedCoopAuthorityKey key) {
        Ref<ChunkStore> chunkRef = info != null ? info.getChunkRef() : null;
        if (chunkRef == null || !chunkRef.isValid()) {
            return false;
        }
        WorldChunk stateChunk = store.getComponent(chunkRef, WorldChunk.getComponentType());
        if (stateChunk == null || stateChunk.getWorld() != expectedWorld) {
            return false;
        }
        int blockIndex = info.getIndex();
        int worldX = ChunkUtil.worldCoordFromLocalCoord(
                stateChunk.getX(), ChunkUtil.xFromBlockInColumn(blockIndex));
        int worldY = ChunkUtil.yFromBlockInColumn(blockIndex);
        int worldZ = ChunkUtil.worldCoordFromLocalCoord(
                stateChunk.getZ(), ChunkUtil.zFromBlockInColumn(blockIndex));
        return worldX == key.x() && worldY == key.y() && worldZ == key.z();
    }

    private BlockTypeMatch matchesBlockType(
            @Nullable String blockTypeId,
            String expectedCoopId) {
        try {
            if (TwCoopConfig.getAssetMap() == null) {
                return new BlockTypeMatch(false, false);
            }
            TwCoopConfig config = TwCoopConfig.resolveForBlockType(blockTypeId);
            return new BlockTypeMatch(true,
                    config != null && config.isManagedAuthorityEnabled()
                            && matchesIdentifier(config.getCoopId(), expectedCoopId));
        } catch (RuntimeException exception) {
            return new BlockTypeMatch(false, false);
        }
    }

    private boolean matchesIdentifier(@Nullable String raw, @Nullable String expected) {
        String value = normalize(raw);
        String target = normalize(expected);
        if (value == null || target == null) {
            return false;
        }
        if (value.equals(target)) {
            return true;
        }
        int separator = Math.max(value.lastIndexOf('/'),
                Math.max(value.lastIndexOf(':'), value.lastIndexOf('.')));
        return separator >= 0 && separator + 1 < value.length()
                && value.substring(separator + 1).equals(target);
    }

    @Nullable
    private String normalize(@Nullable String value) {
        return value == null || value.isBlank()
                ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    @Nonnull
    private ManagedCoopRemovalEvidence.Result deferred(String detail) {
        return new ManagedCoopRemovalEvidence.Result(
                ManagedCoopRemovalEvidence.Status.DEFERRED_AMBIGUOUS, 0, detail);
    }

    private record BlockTypeMatch(boolean available, boolean matching) {
    }
}
