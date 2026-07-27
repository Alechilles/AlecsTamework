package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nullable;

/** Resolves a live owner on its world thread before repairing command items. */
final class CommandPlayerInventoryCanonicalizer {
    private final CommandLinkedNpcInventoryRepairService repairs;

    CommandPlayerInventoryCanonicalizer(CommandLinkedNpcInventoryRepairService repairs) {
        this.repairs = repairs;
    }

    void canonicalize(@Nullable World world, @Nullable UUID playerUuid) {
        if (world == null || playerUuid == null || world.getEntityStore() == null) return;
        Store<EntityStore> store = world.getEntityStore().getStore();
        Ref<EntityStore> ref = world.getEntityRef(playerUuid);
        if (store == null || ref == null || !ref.isValid()) return;
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null && player.getWorld() == world) repairs.canonicalize(player);
    }
}
