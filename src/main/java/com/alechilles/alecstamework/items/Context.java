package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig.CommandEntry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Immutable execution context for a command-item dispatch.
 */
final class Context {
    final Player player;
    final Ref<EntityStore> playerRef;
    final Store<EntityStore> store;
    final TwCommandItemConfig config;
    final CommandEntry command;
    final String itemId;
    final String toolId;
    final Ref<EntityStore> commandTarget;
    final Vector3d raycastPosition;
    ItemStack workingItem;
    boolean itemChanged;
    final boolean blockAllPlayerDamageIfOwned;
    final boolean invulnerableIfOwned;
    final double returnHomeTeleportDistance;
    final double returnHomePathDistanceBeforeTeleport;
    final long returnHomeTeleportDelayMs;
    final double recallSafeSpawnDistance;
    final double recallForceRelocateDistance;

    Context(Player player,
            Ref<EntityStore> playerRef,
            Store<EntityStore> store,
            TwCommandItemConfig config,
            CommandEntry command,
            String itemId,
            String toolId,
            Ref<EntityStore> commandTarget,
            Vector3d raycastPosition,
            ItemStack workingItem,
            boolean blockAllPlayerDamageIfOwned,
            boolean invulnerableIfOwned,
            double returnHomeTeleportDistance,
            double returnHomePathDistanceBeforeTeleport,
            long returnHomeTeleportDelayMs,
            double recallSafeSpawnDistance,
            double recallForceRelocateDistance) {
        this.player = player;
        this.playerRef = playerRef;
        this.store = store;
        this.config = config;
        this.command = command;
        this.itemId = itemId;
        this.toolId = toolId;
        this.commandTarget = commandTarget;
        this.raycastPosition = raycastPosition;
        this.workingItem = workingItem;
        this.blockAllPlayerDamageIfOwned = blockAllPlayerDamageIfOwned;
        this.invulnerableIfOwned = invulnerableIfOwned;
        this.returnHomeTeleportDistance = returnHomeTeleportDistance;
        this.returnHomePathDistanceBeforeTeleport = returnHomePathDistanceBeforeTeleport;
        this.returnHomeTeleportDelayMs = returnHomeTeleportDelayMs;
        this.recallSafeSpawnDistance = recallSafeSpawnDistance;
        this.recallForceRelocateDistance = recallForceRelocateDistance;
    }
}
