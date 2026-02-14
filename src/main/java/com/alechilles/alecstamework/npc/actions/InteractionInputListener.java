package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

/**
 * Records player interaction inputs so action logic can gate on real input events.
 */
public final class InteractionInputListener {
    private final InteractionInputTracker tracker;

    public InteractionInputListener(InteractionInputTracker tracker) {
        this.tracker = tracker;
    }

    /**
     * Captures player interaction inputs against NPC targets.
     */
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event == null || event.isCancelled() || tracker == null) {
            return;
        }
        InteractionType actionType = event.getActionType();
        if (actionType != InteractionType.Use
                && actionType != InteractionType.Primary
                && actionType != InteractionType.Secondary) {
            return;
        }
        Entity target = event.getTargetEntity();
        if (!(target instanceof NPCEntity)) {
            return;
        }
        Ref<EntityStore> targetRef = event.getTargetRef();
        if (targetRef == null || !targetRef.isValid()) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        tracker.recordInteraction(targetRef, player);
    }
}
