package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

public final class ActionTameworkSetOwner extends TameworkActionBase {
    public ActionTameworkSetOwner(BuilderActionTameworkSetOwner builder, BuilderSupport support) {
        super(builder);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        return npcRef != null && npcRef.isValid() && resolveInteractionPlayer(role, infoProvider, store) != null;
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
        if (player == null) {
            return false;
        }
        ComponentType<EntityStore, TameworkOwnerComponent> type = TameworkOwnerComponent.getComponentType();
        if (type == null) {
            return false;
        }
        String ownerName = player.getDisplayName();
        store.putComponent(npcRef, type, new TameworkOwnerComponent(player.getUuid(), ownerName));
        return true;
    }
}
