package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.ownership.LegacyTamedOwnershipBridge;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.UUID;
import javax.annotation.Nullable;

/** Checks ownership/tamed state and sends ownership denial messages. */
final class InteractionOwnershipHelper {
    private final ActionTameworkInteract owner;

    InteractionOwnershipHelper(ActionTameworkInteract owner) {
        this.owner = owner;
    }

    // Returns whether the NPC is marked as tamed.
    boolean isTamed(Ref<EntityStore> npcRef,
                    Store<EntityStore> store,
                    @Nullable InteractionContextSnapshot ctx) {
        if (ctx != null && ctx.cachedNpcTamed != null) {
            return ctx.cachedNpcTamed;
        }
        boolean tamed = TamedStateResolver.isTamed(npcRef, store);
        if (ctx != null) {
            ctx.cachedNpcTamed = tamed;
        }
        return tamed;
    }

    // Returns whether the player is the owner of the NPC.
    boolean isOwner(Ref<EntityStore> npcRef,
                    Store<EntityStore> store,
                    Player player,
                    @Nullable InteractionContextSnapshot ctx) {
        if (ctx != null && ctx.cachedPlayerIsOwner != null) {
            return ctx.cachedPlayerIsOwner;
        }
        if (player == null && (ctx == null || ctx.playerId == null)) {
            return false;
        }
        UUID playerId = ctx != null && ctx.playerId != null ? ctx.playerId : owner.getPlayerUuid(player);
        if (playerId == null) {
            return false;
        }
        UUID ownerId = resolveOwnerId(npcRef, store, ctx);
        boolean isOwner = ownerId != null && ownerId.equals(playerId);
        if (ctx != null) {
            ctx.cachedPlayerIsOwner = isOwner;
        }
        return isOwner;
    }

    // Sends a denial message to non-owners when applicable.
    void maybeNotifyOwnerDenied(Ref<EntityStore> npcRef,
                                Store<EntityStore> store,
                                Player player) {
        if (player == null) {
            return;
        }
        LegacyTamedOwnershipBridge.ClaimResult ownerInfo = LegacyTamedOwnershipBridge.resolveOwner(npcRef, store);
        UUID ownerUuid = ownerInfo.getOwnerId();
        UUID playerUuid = owner.getPlayerUuid(player);
        if (ownerUuid == null || playerUuid == null || ownerUuid.equals(playerUuid)) {
            return;
        }
        String npcName = owner.resolveNpcName(owner.resolveNpcEntity(npcRef, store));
        String ownerName = ownerInfo.getOwnerName();
        OwnerMessageUtil.sendDenied(player, npcName, ownerName, ownerUuid, "interact with");
    }

    @Nullable
    private UUID resolveOwnerId(Ref<EntityStore> npcRef,
                                Store<EntityStore> store,
                                @Nullable InteractionContextSnapshot ctx) {
        if (ctx != null && ctx.cachedNpcOwnerId != null) {
            return ctx.cachedNpcOwnerId;
        }
        UUID ownerId = LegacyTamedOwnershipBridge.resolveOwner(npcRef, store).getOwnerId();
        if (ctx != null) {
            ctx.cachedNpcOwnerId = ownerId;
        }
        return ownerId;
    }
}
