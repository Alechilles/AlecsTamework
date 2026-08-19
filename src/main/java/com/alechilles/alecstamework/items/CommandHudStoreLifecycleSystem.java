package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.StoreSystem;
import com.hypixel.hytale.component.system.tick.TickableSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Seeds one initial recovery round and removes store-scoped HUD state on store unload. */
public final class CommandHudStoreLifecycleSystem extends StoreSystem<EntityStore>
        implements TickableSystem<EntityStore> {
    private final CommandHudDirtySink lifecycleSink;
    private final Object lifecycleLock = new Object();
    private final Set<Store<EntityStore>> pendingStores =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Store<EntityStore>> activeScanStores =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Store<EntityStore>> removedDuringScan =
            Collections.newSetFromMap(new IdentityHashMap<>());

    public CommandHudStoreLifecycleSystem(@Nonnull CommandHudDirtySink lifecycleSink) {
        this.lifecycleSink = lifecycleSink;
    }

    @Override
    public void onSystemAddedToStore(@Nonnull Store<EntityStore> store) {
        synchronized (lifecycleLock) {
            pendingStores.add(store);
        }
    }

    @Override
    public void tick(float ignoredDt, int ignoredSystemIndex, @Nonnull Store<EntityStore> store) {
        synchronized (lifecycleLock) {
            if (!pendingStores.remove(store)) {
                return;
            }
            activeScanStores.add(store);
        }
        try {
            ComponentType<EntityStore, Player> playerType = Player.getComponentType();
            if (playerType == null) {
                return;
            }
            store.forEachChunk(
                    Query.and(playerType),
                    (ArchetypeChunk<EntityStore> chunk, CommandBuffer<EntityStore> ignored) -> {
                        for (int index = 0; index < chunk.size(); index++) {
                            Player player = chunk.getComponent(index, playerType);
                            UUID playerUuid = player != null ? player.getUuid() : null;
                            if (isActiveScan(store)) {
                                lifecycleSink.markRecovery(store, playerUuid);
                            }
                        }
                    }
            );
        } finally {
            finishScan(store);
        }
    }

    @Override
    public void onSystemRemovedFromStore(@Nonnull Store<EntityStore> store) {
        boolean cleanupNow;
        synchronized (lifecycleLock) {
            pendingStores.remove(store);
            if (activeScanStores.remove(store)) {
                removedDuringScan.add(store);
                cleanupNow = false;
            } else if (removedDuringScan.remove(store)) {
                cleanupNow = false;
            } else {
                cleanupNow = true;
            }
        }
        if (cleanupNow) {
            lifecycleSink.removeStore(store);
        }
    }

    private boolean isActiveScan(@Nonnull Store<EntityStore> store) {
        synchronized (lifecycleLock) {
            return activeScanStores.contains(store);
        }
    }

    private void finishScan(@Nonnull Store<EntityStore> store) {
        boolean cleanup;
        synchronized (lifecycleLock) {
            activeScanStores.remove(store);
            cleanup = removedDuringScan.remove(store);
        }
        if (cleanup) {
            lifecycleSink.removeStore(store);
        }
    }
}
