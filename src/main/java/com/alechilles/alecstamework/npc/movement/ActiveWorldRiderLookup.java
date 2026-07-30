package com.alechilles.alecstamework.npc.movement;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nullable;

/** Resolves a rider by stable identity and accepts only a Player in the active world store. */
final class ActiveWorldRiderLookup {
    @Nullable
    private final World world;
    @Nullable
    private final Store<EntityStore> store;

    ActiveWorldRiderLookup(@Nullable World world, @Nullable Store<EntityStore> store) {
        this.world = world;
        this.store = store;
    }

    @Nullable
    Ref<EntityStore> resolve(@Nullable UUID riderUuid) {
        if (riderUuid == null || world == null || store == null) {
            return null;
        }
        Ref<EntityStore> riderRef = world.getEntityRef(riderUuid);
        if (riderRef == null || !riderRef.isValid()) {
            return null;
        }
        Player player = store.getComponent(riderRef, Player.getComponentType());
        return player == null ? null : riderRef;
    }
}
