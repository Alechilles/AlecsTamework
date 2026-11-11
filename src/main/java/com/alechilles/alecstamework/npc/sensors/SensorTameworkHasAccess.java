package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.access.CompanionAccess;
import com.alechilles.alecstamework.api.access.AccessLevel;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkHasAccess;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import javax.annotation.Nonnull;

/**
 * Sensor that matches when the interacting player is the owner or holds shared access to this
 * NPC at the configured level or higher. An unowned NPC never matches.
 */
public final class SensorTameworkHasAccess extends TameworkSensorBase {
    private final AccessLevel minLevel;

    public SensorTameworkHasAccess(@Nonnull BuilderSensorTameworkHasAccess builder,
                                   @Nonnull BuilderSupport support) {
        super(builder);
        this.minLevel = builder.getMinLevel(support);
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        if (!super.matches(ref, role, dt, store)) {
            return false;
        }
        Player player = resolveInteractionPlayer(ref, role, store);
        if (player == null) {
            return false;
        }
        return CompanionAccess.level(ref, store, player.getUuid()).atLeast(minLevel);
    }

    @Override
    public InfoProvider getSensorInfo() {
        return null;
    }
}
