package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.ownership.OwnerMessageUtil;
import com.alechilles.alecstamework.ownership.OwnerNameUtil;
import com.alechilles.alecstamework.ownership.OwnerPopulationCapService;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.UUID;

/**
 * Sets owner component data based on the interacting player.
 */
public final class ActionTameworkSetOwner extends TameworkActionBase {
    private final SetOwnerAppliedEffects appliedEffects;

    public ActionTameworkSetOwner(BuilderActionTameworkSetOwner builder, BuilderSupport support) {
        super(builder);
        this.appliedEffects = new SetOwnerAppliedEffects(builder, support);
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
        UUID playerId = player.getUuid();
        if (playerId == null) {
            return false;
        }
        TameworkOwnerComponent existingOwner = store.getComponent(npcRef, type);
        UUID oldOwnerId = existingOwner == null ? null : existingOwner.getOwnerId();
        if (oldOwnerId == null) {
            OwnerPopulationCapService.Decision decision =
                    OwnerPopulationCapService.evaluateAcquisition(store, playerId);
            if (!decision.allowed()) {
                OwnerMessageUtil.sendPopulationCapReached(
                        player,
                        decision.currentCount(),
                        decision.limit(),
                        decision.scope()
                );
                return false;
            }
        }
        String ownerName = OwnerNameUtil.resolve(player);
        String expectedHeldItemId = appliedEffects.captureHeldItemId(player);
        store.putComponent(
                npcRef,
                type,
                new TameworkOwnerComponent(playerId, ownerName)
        );
        appliedEffects.apply(npcRef, store, playerId, expectedHeldItemId);
        return true;
    }
}
