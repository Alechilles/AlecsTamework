package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.UUID;

/**
 * Blocks owner-restricted interactions and emits a message.
 */
public final class ActionTameworkDenyInteract extends TameworkActionBase {
    public ActionTameworkDenyInteract(BuilderActionTameworkDenyInteract builder, BuilderSupport support) {
        super(builder);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        Player player = resolveInteractionPlayer(role, infoProvider, store);
        if (player == null) {
            return false;
        }
        UUID ownerUuid = resolveOwnerUuid(npcRef, store);
        UUID playerUuid = getPlayerUuid(player);
        return ownerUuid != null && playerUuid != null && !ownerUuid.equals(playerUuid);
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        if (!canExecute(npcRef, role, infoProvider, dt, store)) {
            return false;
        }
        Player player = resolveInteractionPlayer(role, infoProvider, store);
        if (player != null) {
            UUID ownerUuid = resolveOwnerUuid(npcRef, store);
            String ownerName = resolveOwnerName(npcRef, store);
            String npcName = resolveNpcName(resolveNpcEntity(npcRef, store));
            OwnerMessageUtil.sendDenied(player, npcName, ownerName, ownerUuid, "interact with");
        }
        return true;
    }
}
