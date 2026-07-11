package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Resolves immutable loaded-NPC location metadata shared by lifecycle events and bootstrap scans. */
final class LoadedNpcLocationResolver {
    private LoadedNpcLocationResolver() {
    }

    @Nonnull
    static LoadedNpcIdentityIndex.Location resolve(@Nonnull Store<EntityStore> store) {
        World world = store.getExternalData() != null ? store.getExternalData().getWorld() : null;
        String worldName = world != null ? world.getName() : null;
        String storeIdentity = "entity-store#" + store.getStoreIndex() + "@"
                + Integer.toUnsignedString(System.identityHashCode(store), 16);
        return new LoadedNpcIdentityIndex.Location(worldName, storeIdentity);
    }
}
