package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.progression.NeedsResourceFastModePolicy;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedsResourceFastMode;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import javax.annotation.Nonnull;

/**
 * Sensor that matches when the global needs resource fast-consume policy is active.
 */
public final class SensorTameworkNeedsResourceFastMode extends TameworkSensorBase {
    public SensorTameworkNeedsResourceFastMode(@Nonnull BuilderSensorTameworkNeedsResourceFastMode builder) {
        super(builder);
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        return super.matches(ref, role, dt, store)
                && NeedsResourceFastModePolicy.isFastModeActive(System.currentTimeMillis());
    }

    @Override
    public InfoProvider getSensorInfo() {
        return null;
    }
}
