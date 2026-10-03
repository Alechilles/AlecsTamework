package com.alechilles.alecstamework.compat;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * Reads and writes block state through chunk sections. Update 7 removed the WorldChunk block
 * accessors, so callers must not go back to them.
 */
public final class HytaleBlockStateAccess {
    private HytaleBlockStateAccess() {
    }

    /**
     * Resolves the loaded block location of a block entity. Returns null when the value is not a
     * {@link BlockModule.BlockStateInfo}, when its section or column is unavailable, or when the
     * engine lookup fails.
     */
    @Nullable
    public static BlockLocation resolve(@Nullable Store<ChunkStore> store,
                                        @Nullable Object blockStateInfo) {
        if (store == null || !(blockStateInfo instanceof BlockModule.BlockStateInfo info)) {
            return null;
        }
        try {
            return resolveSectionLocation(store, info);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    public static BlockType blockTypeAt(@Nullable WorldChunk chunk, int x, int y, int z) {
        int blockId = blockIdAt(chunk, x, y, z);
        return blockId < 0 ? null : BlockType.getAssetMap().getAsset(blockId);
    }

    /** Returns -1 when the requested block section is unavailable. */
    public static int blockIdAt(@Nullable WorldChunk chunk, int x, int y, int z) {
        if (chunk == null || !sameColumn(chunk, x, z)) {
            return -1;
        }
        BlockSection section = blockSectionAt(chunk, x, y, z);
        return section == null ? -1 : section.get(x, y, z);
    }

    /** Returns 0 (no fluid) when the requested section is unavailable. Call on the world thread. */
    public static int fluidIdAt(@Nullable WorldChunk chunk, int x, int y, int z) {
        if (chunk == null || !sameColumn(chunk, x, z)) {
            return 0;
        }
        ChunkStore chunkStore = chunkStore(chunk);
        Ref<ChunkStore> sectionRef = sectionRefAt(chunkStore, x, y, z);
        FluidSection section = sectionRef == null ? null
                : chunkStore.getStore().getComponent(sectionRef, FluidSection.getComponentType());
        return section == null ? 0 : section.getFluidId(x, y, z);
    }

    public static int rotationAt(@Nullable WorldChunk chunk, int x, int y, int z) {
        if (chunk == null || !sameColumn(chunk, x, z)) {
            return 0;
        }
        BlockSection section = blockSectionAt(chunk, x, y, z);
        return section == null ? 0 : section.getRotationIndex(x, y, z);
    }

    /**
     * Returns the block entity reference at the position, or null when the block has none or its
     * section is not loaded. The reference may be invalid. Call on the world thread.
     */
    @Nullable
    public static Ref<ChunkStore> blockEntityRefAt(@Nullable WorldChunk chunk, int x, int y, int z) {
        if (chunk == null || !sameColumn(chunk, x, z)) {
            return null;
        }
        ChunkStore chunkStore = chunkStore(chunk);
        Ref<ChunkStore> sectionRef = sectionRefAt(chunkStore, x, y, z);
        BlockComponentSection section = sectionRef == null ? null
                : chunkStore.getStore().getComponent(sectionRef, BlockComponentSection.getComponentType());
        return section == null ? null : section.getBlockReference(ChunkUtil.indexBlock(x, y, z));
    }

    public static boolean setBlock(@Nullable WorldChunk chunk, int x, int y, int z,
                                   int id, @Nonnull BlockType type, int rotation,
                                   int filler, int settings) {
        if (chunk == null || !sameColumn(chunk, x, z)) {
            return false;
        }
        ChunkStore chunkStore = chunkStore(chunk);
        Ref<ChunkStore> sectionRef = sectionRefAt(chunkStore, x, y, z);
        return sectionRef != null && BlockOperations.setBlock(
                chunkStore, sectionRef, x, y, z, id, type, rotation, filler, settings);
    }

    public static void setInteractionState(@Nullable WorldChunk chunk, int x, int y, int z,
                                           @Nonnull BlockType type, @Nonnull String state) {
        if (chunk == null || !sameColumn(chunk, x, z)) {
            return;
        }
        ChunkStore chunkStore = chunkStore(chunk);
        Ref<ChunkStore> sectionRef = sectionRefAt(chunkStore, x, y, z);
        if (sectionRef != null) {
            BlockOperations.setBlockInteractionState(chunkStore, sectionRef,
                    x, y, z, type, state, false);
        }
    }

    private static boolean sameColumn(@Nonnull WorldChunk chunk, int x, int z) {
        return chunk.getIndex() == ChunkUtil.indexChunkFromBlock(x, z);
    }

    @Nullable
    private static ChunkStore chunkStore(@Nonnull WorldChunk chunk) {
        Ref<ChunkStore> ref = chunk.getReference();
        return ref != null && ref.isValid() ? ref.getStore().getExternalData() : null;
    }

    @Nullable
    private static Ref<ChunkStore> sectionRefAt(@Nullable ChunkStore chunkStore,
                                                int x, int y, int z) {
        if (chunkStore == null) {
            return null;
        }
        Ref<ChunkStore> ref = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
        return ref != null && ref.isValid() ? ref : null;
    }

    @Nullable
    private static BlockSection blockSectionAt(@Nonnull WorldChunk chunk, int x, int y, int z) {
        ChunkStore chunkStore = chunkStore(chunk);
        Ref<ChunkStore> sectionRef = sectionRefAt(chunkStore, x, y, z);
        return sectionRef == null ? null
                : chunkStore.getStore().getComponent(sectionRef, BlockSection.getComponentType());
    }

    @Nullable
    private static BlockLocation resolveSectionLocation(@Nonnull Store<ChunkStore> store,
                                                        @Nonnull BlockModule.BlockStateInfo info) {
        Vector3i position = new Vector3i();
        boolean resolved = info.fillWorldPos(store, position);
        Ref<ChunkStore> sectionRef = info.getSectionRef();
        if (!resolved || sectionRef == null || !sectionRef.isValid()) {
            return null;
        }
        ChunkSection section = store.getComponent(sectionRef, ChunkSection.getComponentType());
        if (section == null) {
            return null;
        }
        Ref<ChunkStore> columnRef = section.getChunkColumnReference();
        WorldChunk chunk = columnRef == null || !columnRef.isValid()
                ? null : store.getComponent(columnRef, WorldChunk.getComponentType());
        return chunk == null ? null : new BlockLocation(chunk, position.x, position.y, position.z);
    }

    /** A loaded block location and its owning chunk column. */
    public record BlockLocation(@Nonnull WorldChunk chunk, int x, int y, int z) {
    }
}
