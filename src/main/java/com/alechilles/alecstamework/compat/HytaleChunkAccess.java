package com.alechilles.alecstamework.compat;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Chunk lookups and ownership checks that work on both Update 6 and Update 7.
 */
public final class HytaleChunkAccess {
    private HytaleChunkAccess() {
    }

    /** Checks the owning store, since Update 7 no longer exposes WorldChunk.getWorld(). */
    public static boolean isOwnedBy(@Nullable WorldChunk chunk, @Nullable World world) {
        if (chunk == null || world == null) {
            return false;
        }
        Ref<ChunkStore> ref = chunk.getReference();
        if (ref == null || !ref.isValid()) {
            return false;
        }
        ChunkStore owner = ref.getStore().getExternalData();
        return owner != null && owner.getWorld() == world;
    }

    /**
     * The chunk if it is in memory, or null. Update 7 removed {@code World.getChunkIfInMemory},
     * so this reads the chunk component from its reference as that method did.
     */
    @Nullable
    public static WorldChunk chunkIfInMemory(@Nullable World world, long index) {
        ChunkStore chunkStore = world == null ? null : world.getChunkStore();
        if (chunkStore == null) {
            return null;
        }
        Ref<ChunkStore> ref = chunkStore.getChunkReference(index);
        if (ref == null) {
            return null;
        }
        if (!world.isInThread()) {
            return CompletableFuture.supplyAsync(() -> chunkIfInMemory(world, index), world).join();
        }
        return ref.isValid() ? chunkStore.getStore().getComponent(ref, WorldChunk.getComponentType()) : null;
    }

    /**
     * True when the chunk at the position is loaded but not ticking (in a player's view distance,
     * outside the range the world simulates). The engine does not keep an entity added there in
     * the store: it parks the entity with its chunk section, to load when the section ticks, and
     * {@code Store#addEntity} returns null as for a refused add. A caller that takes that null as
     * "nothing was added" and tries again leaves one saved copy per attempt, so check this first.
     * False for a ticking chunk and for one that is not loaded. World thread only.
     */
    public static boolean parksEntitiesAt(@Nonnull World world, double x, double y, double z) {
        ChunkStore chunkStore = world.getChunkStore();
        if (chunkStore == null) {
            return false;
        }
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        return nonTicking(chunkStore, chunkStore.getChunkSectionReferenceAtBlock(blockX, blockY, blockZ))
                || nonTicking(chunkStore, chunkStore.getChunkReference(ChunkUtil.indexChunkFromBlock(blockX, blockZ)));
    }

    private static boolean nonTicking(ChunkStore chunkStore, @Nullable Ref<ChunkStore> ref) {
        return ref != null && ref.isValid()
                && chunkStore.getStore().getArchetype(ref).contains(ChunkStore.REGISTRY.getNonTickingComponentType());
    }

    /** Loads the chunk as ticking, replacing {@code World.getChunkAsync} which Update 7 removed. */
    @Nonnull
    public static CompletableFuture<WorldChunk> chunkAsync(@Nonnull World world, long index) {
        ChunkStore chunkStore = world.getChunkStore();
        return chunkStore.getChunkReferenceAsync(index, GetChunkFlags.SET_TICKING).thenApplyAsync(
                ref -> ref == null || !ref.isValid() ? null
                        : chunkStore.getStore().getComponent(ref, WorldChunk.getComponentType()),
                world);
    }
}
