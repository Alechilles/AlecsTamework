package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.progression.NeedsResourceConsumeAttemptTracker;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedsResourceConsumeSucceeded;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sensor that matches shortly after a successful needs-resource consume attempt.
 */
public final class SensorTameworkNeedsResourceConsumeSucceeded extends TameworkSensorBase {
    @Nonnull
    private final String resourceType;
    private final long maxAgeMs;

    public SensorTameworkNeedsResourceConsumeSucceeded(
            @Nonnull BuilderSensorTameworkNeedsResourceConsumeSucceeded builder,
            @Nonnull BuilderSupport support) {
        super(builder);
        this.resourceType = builder.getResourceType(support);
        this.maxAgeMs = sanitizeMaxAgeMs(builder.getMaxAgeSeconds(support));
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        if (!super.matches(ref, role, dt, store)) {
            return false;
        }
        return NeedsResourceConsumeAttemptTracker.wasSuccessful(
                resolveNpcUuid(ref, store),
                resourceType,
                System.currentTimeMillis(),
                maxAgeMs
        );
    }

    @Override
    public InfoProvider getSensorInfo() {
        return null;
    }

    @Nullable
    private static UUID resolveNpcUuid(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        return npc != null ? npc.getUuid() : null;
    }

    private static long sanitizeMaxAgeMs(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            return 5_000L;
        }
        return Math.max(1L, (long) (seconds * 1_000.0));
    }
}
