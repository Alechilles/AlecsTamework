package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nonnull;

/** Seeds command HUD work when a player joins and clears its state when the player leaves. */
public final class CommandHudPlayerLifecycleSystem extends RefSystem<EntityStore> {
    private final CommandHudDirtySink lifecycleSink;

    public CommandHudPlayerLifecycleSystem(@Nonnull CommandHudDirtySink lifecycleSink) {
        this.lifecycleSink = lifecycleSink;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<EntityStore> reference,
                              @Nonnull AddReason reason,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Player player = store.getComponent(reference, Player.getComponentType());
        UUID playerUuid = player != null ? player.getUuid() : null;
        lifecycleSink.markDirty(store, playerUuid);
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<EntityStore> reference,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<EntityStore> store,
                               @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Player player = store.getComponent(reference, Player.getComponentType());
        UUID playerUuid = player != null ? player.getUuid() : null;
        lifecycleSink.remove(store, playerUuid);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType());
    }
}
