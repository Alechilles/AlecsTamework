package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkHasOwner;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.UUID;
import javax.annotation.Nonnull;

/**
 * Sensor that matches when an NPC has any owner.
 */
public class SensorTameworkHasOwner extends TameworkSensorBase {
    public SensorTameworkHasOwner(@Nonnull BuilderSensorTameworkHasOwner builder, @Nonnull BuilderSupport support) {
        super(builder);
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref, @Nonnull Role role, double dt, @Nonnull Store<EntityStore> store) {
        if (!super.matches(ref, role, dt, store)) {
            return false;
        }
        UUID ownerUuid = resolveOwnerUuid(ref, store);
        return ownerUuid != null;
    }

    @Override
    public InfoProvider getSensorInfo() {
        return null;
    }
}
