package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.corecomponents.SensorBase;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderSensorBase;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Shared helpers for Tamework sensors.
 */
public abstract class TameworkSensorBase extends SensorBase {
    protected TameworkSensorBase(@Nonnull BuilderSensorBase builder) {
        super(builder);
    }

    // Prefer the current interaction target; fall back to info-provider targets.
    protected Player resolveInteractionPlayer(Role role, Store<EntityStore> store) {
        if (role == null || role.getStateSupport() == null) {
            return null;
        }
        Ref<EntityStore> target = role.getStateSupport().getInteractionIterationTarget();
        if (target == null || !target.isValid()) {
            return null;
        }
        return store.getComponent(target, Player.getComponentType());
    }

    // Reads the owner UUID from the component, if present.
    protected UUID resolveOwnerUuid(Ref<EntityStore> npcRef, Store<EntityStore> store) {
        if (npcRef == null || !npcRef.isValid()) {
            return null;
        }
        TameworkOwnerComponent component = store.getComponent(npcRef, TameworkOwnerComponent.getComponentType());
        if (component != null) {
            return component.getOwnerId();
        }
        return null;
    }
}
