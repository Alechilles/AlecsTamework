package com.alechilles.alecstamework.items;

import com.hypixel.hytale.builtin.adventure.farming.config.FarmingCoopAsset;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/** Reads exact block/config evidence for captured-item intake without invoking vanilla occupancy. */
public final class HytaleManagedCoopItemTargetResolver {
    private final ManagedCoopAuthorityResolver authority;

    public HytaleManagedCoopItemTargetResolver() {
        this(new ManagedCoopAuthorityResolver());
    }

    HytaleManagedCoopItemTargetResolver(@Nonnull ManagedCoopAuthorityResolver authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    /** Returns null only when the target has no enabled, authority-eligible managed config. */
    @Nullable
    public ManagedCoopContext resolve(@Nonnull World world, @Nonnull Vector3i targetBlock) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(targetBlock, "targetBlock");
        if (world.getChunkStore() == null) {
            return null;
        }
        long chunkIndex = ChunkUtil.indexChunkFromBlock(targetBlock.x, targetBlock.z);
        WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
        if (chunk == null) {
            return null;
        }
        BlockType blockType = chunk.getBlockType(targetBlock.x, targetBlock.y, targetBlock.z);
        String blockTypeId = blockType != null ? blockType.getId() : null;
        String coopAssetId = resolveCoopAssetId(world, chunk, targetBlock);
        return authority.resolve(
                world.getName(),
                blockTypeId,
                coopAssetId,
                targetBlock,
                chunk.getRotationIndex(targetBlock.x, targetBlock.y, targetBlock.z),
                null
        );
    }

    @Nullable
    private String resolveCoopAssetId(World world, WorldChunk chunk, Vector3i block) {
        Ref<ChunkStore> blockRef = chunk.getBlockComponentEntity(block.x, block.y, block.z);
        if (blockRef == null || !blockRef.isValid()) {
            return null;
        }
        CoopBlock coop = world.getChunkStore().getStore().getComponent(
                blockRef, CoopBlock.getComponentType());
        FarmingCoopAsset asset = coop != null ? coop.getCoopAsset() : null;
        return asset != null ? asset.getId() : null;
    }
}
