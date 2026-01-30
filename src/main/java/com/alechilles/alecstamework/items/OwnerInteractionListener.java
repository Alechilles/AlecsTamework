package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ownership.OwnerStore;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import java.util.UUID;
import java.util.logging.Level;

public final class OwnerInteractionListener {
    private final OwnerStore ownerStore;
    private final HytaleLogger logger;

    public OwnerInteractionListener(OwnerStore ownerStore, HytaleLogger logger) {
        this.ownerStore = ownerStore;
        this.logger = logger;
    }

    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event == null || ownerStore == null) {
            return;
        }
        if (event.getActionType() != InteractionType.Use) {
            return;
        }
        Entity target = event.getTargetEntity();
        if (!(target instanceof NPCEntity)) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        UUID ownerUuid = ownerStore.getOwner(target.getUuid());
        if (ownerUuid == null || ownerUuid.equals(player.getUuid())) {
            return;
        }
        event.setCancelled(true);
        logger.at(Level.INFO).log(
                "Owner restrict: denied interaction player=" + player.getDisplayName()
                        + " target=" + target.getUuid()
        );
    }
}
