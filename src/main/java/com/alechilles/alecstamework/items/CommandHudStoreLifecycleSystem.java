package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.StoreSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Seeds one initial recovery round and removes store-scoped HUD state on store unload. */
public final class CommandHudStoreLifecycleSystem extends StoreSystem<EntityStore> {
    private final CommandHudDirtySink lifecycleSink;

    public CommandHudStoreLifecycleSystem(@Nonnull CommandHudDirtySink lifecycleSink) {
        this.lifecycleSink = lifecycleSink;
    }

    @Override
    public void onSystemAddedToStore(@Nonnull Store<EntityStore> store) {
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
                        lifecycleSink.markRecovery(store, playerUuid);
                    }
                }
        );
    }

    @Override
    public void onSystemRemovedFromStore(@Nonnull Store<EntityStore> store) {
        lifecycleSink.removeStore(store);
    }
}
