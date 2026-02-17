package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.TamedStateResolver;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkIsTamed;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import javax.annotation.Nonnull;

/**
 * Sensor that matches when an NPC is tamed.
 */
public final class SensorTameworkIsTamed extends com.hypixel.hytale.server.npc.corecomponents.SensorBase {
    public SensorTameworkIsTamed(@Nonnull BuilderSensorTameworkIsTamed builder, @Nonnull BuilderSupport support) {
        super(builder);
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref, @Nonnull Role role, double dt, @Nonnull Store<EntityStore> store) {
        return TamedStateResolver.isTamed(ref, store);
    }

    @Override
    public InfoProvider getSensorInfo() {
        return null;
    }
}
