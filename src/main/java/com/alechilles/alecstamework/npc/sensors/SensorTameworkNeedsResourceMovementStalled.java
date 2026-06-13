package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.progression.NeedsResourceMovementProgressTracker;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedsResourceMovementStalled;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Detects a needs-seek move that has stayed outside consume range without closing distance to its target.
 */
public final class SensorTameworkNeedsResourceMovementStalled extends TameworkSensorBase {
    @Nonnull
    private final String resourceType;
    private final double graceSeconds;
    private final double minProgress;

    public SensorTameworkNeedsResourceMovementStalled(
            @Nonnull BuilderSensorTameworkNeedsResourceMovementStalled builder,
            @Nonnull BuilderSupport support) {
        super(builder);
        this.resourceType = builder.getResourceType(support);
        this.graceSeconds = builder.getGraceSeconds(support);
        this.minProgress = builder.getMinProgress(support);
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        if (!super.matches(ref, role, dt, store)) {
            return false;
        }
        UUID npcUuid = resolveNpcUuid(ref, store);
        Vector3d currentPosition = resolvePosition(ref, store);
        return NeedsResourceMovementProgressTracker.isStalled(
                npcUuid,
                resourceType,
                currentPosition,
                System.currentTimeMillis(),
                graceSeconds,
                minProgress
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

    @Nullable
    private static Vector3d resolvePosition(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        return transform == null ? null : transform.getPosition();
    }
}
