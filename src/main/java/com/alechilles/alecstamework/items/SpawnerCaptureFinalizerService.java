package com.alechilles.alecstamework.items;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

/**
 * Applies capture-side entity cleanup after a successful spawner capture.
 */
public final class SpawnerCaptureFinalizerService {
    public void despawnNpc(Player player, Ref<EntityStore> targetRef, Entity targetEntity) {
        if (player == null) {
            return;
        }
        if (targetEntity instanceof NPCEntity npcEntity) {
            npcEntity.setToDespawn();
            return;
        }
        if (targetRef == null || !targetRef.isValid()) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        NPCEntity npc = store.getComponent(targetRef, NPCEntity.getComponentType());
        if (npc != null) {
            npc.setToDespawn();
        }
    }
}
