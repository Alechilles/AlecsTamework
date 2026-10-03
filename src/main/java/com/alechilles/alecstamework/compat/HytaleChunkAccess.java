package com.alechilles.alecstamework.compat;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nullable;

/**
 * Chunk ownership checks that work on both Update 6 and Update 7.
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
}
