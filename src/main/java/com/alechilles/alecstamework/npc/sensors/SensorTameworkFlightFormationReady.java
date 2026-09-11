package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.movement.BodyMotionTameworkFlightFormation;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkFlightFormationReady;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Selects formation movement only while an autonomous flying follower has a flying leader. */
public final class SensorTameworkFlightFormationReady extends TameworkSensorBase {
    private final boolean enabled;

    public SensorTameworkFlightFormationReady(@Nonnull BuilderSensorTameworkFlightFormationReady builder,
                                               boolean enabled) {
        super(builder);
        this.enabled = enabled;
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref, @Nonnull Role role,
                           double dt, @Nonnull Store<EntityStore> store) {
        return enabled && BodyMotionTameworkFlightFormation.findFlyingLeader(ref, role, store) != null;
    }

    @Nullable
    @Override
    public InfoProvider getSensorInfo() {
        return null;
    }
}
