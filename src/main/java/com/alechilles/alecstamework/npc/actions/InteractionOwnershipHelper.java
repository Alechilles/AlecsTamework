package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;

/** Checks ownership/tamed state and sends ownership denial messages. */
final class InteractionOwnershipHelper {
    private final ActionTameworkInteract owner;

    InteractionOwnershipHelper(ActionTameworkInteract owner) {
        this.owner = owner;
    }

    // Returns whether the NPC is marked as tamed.
    boolean isTamed(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        return TamedStateResolver.isTamed(npcRef, store);
    }

    // Returns whether the player is the owner of the NPC.
    boolean isOwner(Ref<EntityStore> npcRef, Store<EntityStore> store, Player player) {
        if (player == null) {
            return false;
        }
        UUID ownerId = owner.resolveOwnerUuid(npcRef, store);
        return ownerId != null && ownerId.equals(owner.getPlayerUuid(player));
    }

    // Sends a denial message to non-owners when applicable.
    void maybeNotifyOwnerDenied(Ref<EntityStore> npcRef,
                                Store<EntityStore> store,
                                Player player) {
        if (player == null) {
            return;
        }
        UUID ownerUuid = owner.resolveOwnerUuid(npcRef, store);
        UUID playerUuid = owner.getPlayerUuid(player);
        if (ownerUuid == null || playerUuid == null || ownerUuid.equals(playerUuid)) {
            return;
        }
        String npcName = owner.resolveNpcName(owner.resolveNpcEntity(npcRef, store));
        String ownerName = owner.resolveOwnerName(npcRef, store);
        OwnerMessageUtil.sendDenied(player, npcName, ownerName, ownerUuid, "interact with");
    }
}
