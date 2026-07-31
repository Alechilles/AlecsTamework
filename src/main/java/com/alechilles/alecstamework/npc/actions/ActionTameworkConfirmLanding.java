package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerWalk;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

/** Switches a flying NPC to its walk controller after authoritative ground contact. */
public final class ActionTameworkConfirmLanding extends TameworkActionBase {
    public ActionTameworkConfirmLanding(BuilderActionTameworkConfirmLanding builder) {
        super(builder);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || role == null || store == null) {
            return false;
        }
        MotionController controller = role.getActiveMotionController();
        return controller != null
                && LandingContactTransition.canConfirm(controller.getType(), controller.onGround())
                && store.getComponent(npcRef, NPCEntity.getComponentType()) != null;
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid() || role == null || store == null) {
            return false;
        }
        MotionController controller = role.getActiveMotionController();
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (controller == null || npc == null) {
            return false;
        }
        return LandingContactTransition.confirm(
                controller.getType(),
                controller.onGround(),
                () -> role.setActiveMotionController(npcRef, npc, MotionControllerWalk.TYPE, store)
        );
    }
}
