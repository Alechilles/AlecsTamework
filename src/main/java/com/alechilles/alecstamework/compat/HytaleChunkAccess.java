package com.alechilles.alecstamework.compat;

import com.hypixel.hytale.component.Ref;
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
