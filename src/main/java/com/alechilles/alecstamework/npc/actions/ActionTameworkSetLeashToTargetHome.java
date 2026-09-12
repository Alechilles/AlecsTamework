package com.alechilles.alecstamework.npc.actions;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.IPositionProvider;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import javax.annotation.Nullable;

/** Copies the current entity sensor target's persistent NPC home point to this NPC's leash. */
public final class ActionTameworkSetLeashToTargetHome extends TameworkActionBase {
    public ActionTameworkSetLeashToTargetHome(BuilderActionTameworkSetLeashToTargetHome builder) {
        super(builder);
    }

    @Override
    public boolean canExecute(Ref<EntityStore> npcRef,
                              Role role,
                              InfoProvider infoProvider,
                              double dt,
                              Store<EntityStore> store) {
        return npcRef != null && npcRef.isValid() && store != null && resolveTarget(infoProvider) != null;
    }

    @Override
    public boolean execute(Ref<EntityStore> npcRef,
                           Role role,
                           InfoProvider infoProvider,
                           double dt,
                           Store<EntityStore> store) {
        Ref<EntityStore> targetRef = resolveTarget(infoProvider);
        if (npcRef == null || !npcRef.isValid() || store == null || targetRef == null) {
            return false;
        }
        NPCEntity self = store.getComponent(npcRef, NPCEntity.getComponentType());
        NPCEntity target = store.getComponent(targetRef, NPCEntity.getComponentType());
        return copyTargetHome(self, target);
    }

    static boolean copyTargetHome(@Nullable NPCEntity self, @Nullable NPCEntity target) {
        if (self == null || target == null || target.getLeashPoint() == null) {
            return false;
        }
        self.setLeashPoint(target.getLeashPoint());
        self.setLeashHeading(target.getLeashHeading());
        self.setLeashPitch(target.getLeashPitch());
        return true;
    }

    @Nullable
    private static Ref<EntityStore> resolveTarget(@Nullable InfoProvider infoProvider) {
        if (infoProvider == null || !infoProvider.hasPosition()) {
            return null;
        }
        IPositionProvider provider = infoProvider.getPositionProvider();
        if (provider == null) {
            return null;
        }
        Ref<EntityStore> target = provider.getTarget();
        return target != null && target.isValid() ? target : null;
    }
}
