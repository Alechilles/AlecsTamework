package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.TameworkAmbientHerdRuntimeParticipants;
import com.alechilles.alecstamework.npc.ambient.AmbientHerdCoordinator;
import com.alechilles.alecstamework.npc.ambient.AmbientHerdCoordinator.SensorPhase;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfo;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfoProvider;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkAmbientHerd;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;

/** Reuses one target provider per instruction; all live access stays in the NPC callback. */
public final class SensorTameworkAmbientHerd extends TameworkSensorBase {
    private final boolean enabled;
    private final boolean canLead;
    private final SensorPhase phase;
    private final TameworkTargetPositionInfo target = new TameworkTargetPositionInfo();
    private final InfoProvider info = new TameworkTargetPositionInfoProvider(null, target);

    public SensorTameworkAmbientHerd(BuilderSensorTameworkAmbientHerd builder, boolean enabled,
                                     boolean canLead, SensorPhase phase) {
        super(builder);
        this.enabled = enabled;
        this.canLead = canLead;
        this.phase = phase;
    }

    @Override
    public boolean matches(Ref<EntityStore> ref, Role role, double dt, Store<EntityStore> store) {
        target.clear();
        AmbientHerdCoordinator coordinator = TameworkAmbientHerdRuntimeParticipants.current();
        if (!enabled || coordinator == null || !ref.isValid()) {
            return false;
        }
        if (phase == SensorPhase.READY) {
            coordinator.observe(store, ref, role, canLead, System.nanoTime() / 1_000_000L);
        }
        return coordinator.read(store, ref, phase, target);
    }

    @Override
    public InfoProvider getSensorInfo() { return info; }
}
