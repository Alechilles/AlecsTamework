package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.TameworkAmbientHerdRuntimeParticipants;
import com.alechilles.alecstamework.npc.ambient.AmbientHerdCoordinator;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

public final class ActionTameworkAmbientHerdCancel extends TameworkActionBase {
    public ActionTameworkAmbientHerdCancel(BuilderActionTameworkAmbientHerdCancel builder) { super(builder); }

    @Override
    public boolean execute(Ref<EntityStore> ref, Role role, InfoProvider info, double dt, Store<EntityStore> store) {
        AmbientHerdCoordinator coordinator = TameworkAmbientHerdRuntimeParticipants.current();
        if (coordinator != null && ref != null && ref.isValid()) {
            coordinator.cancel(store, ref, AmbientHerdCoordinator.Exit.MEMBER_CHANGED);
        }
        return true;
    }
}
