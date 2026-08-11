package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.Tamework;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.corecomponents.SensorBase;
import com.hypixel.hytale.server.npc.corecomponents.builders.BuilderSensorBase;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.role.Role;
import java.util.UUID;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import javax.annotation.Nonnull;

/**
 * Shared helpers for Tamework sensors.
 */
public abstract class TameworkSensorBase extends SensorBase {
    protected TameworkSensorBase(@Nonnull BuilderSensorBase builder) {
        super(builder);
    }

    /**
     * Update 5 callback retained for one JAR that can run on both supported API generations.
     */
    public boolean matches(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        return !once || !triggered;
    }

    @Override
    public final boolean matches(@Nonnull Ref<EntityStore> ref,
                                 @Nonnull ExecutionSupport support,
                                 double dt,
                                 @Nonnull Store<EntityStore> store) {
        ExecutionSupport previous = NpcSupportAccess.push(support);
        try {
            return matches(ref, support.getRole(), dt, store);
        } finally {
            NpcSupportAccess.restore(previous);
        }
    }

    // Prefer the current interaction target; fall back to info-provider targets.
    protected Player resolveInteractionPlayer(Ref<EntityStore> npcRef,
                                              Role role,
                                              Store<EntityStore> store) {
        StateSupport stateSupport = NpcSupportAccess.state(role, npcRef, store);
        if (stateSupport == null) {
            return null;
        }
        Ref<EntityStore> target = stateSupport.getInteractionIterationTarget();
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
