package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.access.CompanionAccess;
import com.alechilles.alecstamework.api.access.AccessAction;
import com.alechilles.alecstamework.api.access.AccessLevel;
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
        return accessLevel(npcRef, store, player, ctx) == AccessLevel.OWNER;
    }

    /**
     * Returns whether the player's access to the NPC allows the action: the owner always, a sharer when
     * their level covers it. False for an unowned NPC.
     */
    boolean allows(Ref<EntityStore> npcRef,
                   Store<EntityStore> store,
                   Player player,
                   @Nullable InteractionContextSnapshot ctx,
                   AccessAction action) {
        AccessLevel level = accessLevel(npcRef, store, player, ctx);
        boolean allowed = action.allowedAt(level);
        if (!allowed && ctx != null && level != AccessLevel.NONE) {
            // Lets the no-match path tell a sharer that their level was the reason.
            ctx.accessDenied = true;
        }
        return allowed;
    }

    // Returns the player's access level on the NPC; NONE for an unowned NPC.
    AccessLevel accessLevel(Ref<EntityStore> npcRef,
                            Store<EntityStore> store,
                            Player player,
                            @Nullable InteractionContextSnapshot ctx) {
        if (ctx != null && ctx.cachedPlayerAccessLevel != null) {
            return ctx.cachedPlayerAccessLevel;
        }
        if (player == null && (ctx == null || ctx.playerId == null)) {
            return AccessLevel.NONE;
        }
        UUID playerId = ctx != null && ctx.playerId != null ? ctx.playerId : owner.getPlayerUuid(player);
        if (playerId == null) {
            return AccessLevel.NONE;
        }
        UUID ownerId = resolveOwnerId(npcRef, store, ctx);
        AccessLevel level = CompanionAccess.level(ownerId, playerId, npcRef, store);
        if (ctx != null) {
            ctx.cachedPlayerAccessLevel = level;
        }
        return level;
    }

    // Sends a denial message to non-owners when applicable; ctx carries whether an entry was
    // refused for the player's shared access level.
    void maybeNotifyOwnerDenied(Ref<EntityStore> npcRef,
                                Store<EntityStore> store,
                                Player player,
                                @Nullable InteractionContextSnapshot ctx) {
        if (player == null) {
            return;
        }
        LegacyTamedOwnershipBridge.ClaimResult ownerInfo = LegacyTamedOwnershipBridge.resolveOwner(npcRef, store);
        UUID ownerUuid = ownerInfo.getOwnerId();
        UUID playerUuid = owner.getPlayerUuid(player);
        if (ownerUuid == null || playerUuid == null || ownerUuid.equals(playerUuid)) {
            return;
        }
        // A sharer who matched nothing is like an owner who matched nothing, unless an entry was
        // refused for their level.
        if ((ctx == null || !ctx.accessDenied)
                && CompanionAccess.level(ownerUuid, playerUuid, npcRef, store) != AccessLevel.NONE) {
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
