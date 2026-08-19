package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.StoreSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/** Removes store-scoped command HUD state when an entity store unloads. */
public final class CommandHudStoreLifecycleSystem extends StoreSystem<EntityStore> {
    private final CommandHudDirtySink lifecycleSink;

    public CommandHudStoreLifecycleSystem(@Nonnull CommandHudDirtySink lifecycleSink) {
        this.lifecycleSink = lifecycleSink;
    }

    @Override
    public void onSystemAddedToStore(@Nonnull Store<EntityStore> store) {
        // State is created lazily by the HUD services on their first sweep.
    }

    @Override
    public void onSystemRemovedFromStore(@Nonnull Store<EntityStore> store) {
        lifecycleSink.removeStore(store);
    }
}
