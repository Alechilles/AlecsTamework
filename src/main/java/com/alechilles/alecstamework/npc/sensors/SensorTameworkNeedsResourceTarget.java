package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.progression.CompanionNeedsEnvironmentService;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfo;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfoProvider;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedsResourceTarget;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sensor that resolves and exposes a nearby needs-resource target position.
 */
public final class SensorTameworkNeedsResourceTarget extends TameworkSensorBase {
    private static final CompanionNeedsEnvironmentService ENVIRONMENT_SERVICE = new CompanionNeedsEnvironmentService();

    private final ResourceType resourceType;
    private final double range;
    private final String[] itemIds;
    private final TameworkTargetPositionInfo positionInfo = new TameworkTargetPositionInfo();
    private final TameworkTargetPositionInfoProvider infoProvider =
            new TameworkTargetPositionInfoProvider(null, positionInfo);

    public SensorTameworkNeedsResourceTarget(@Nonnull BuilderSensorTameworkNeedsResourceTarget builder,
                                             @Nonnull BuilderSupport support) {
        super(builder);
        this.resourceType = ResourceType.from(builder.getResourceType(support));
        this.range = sanitizeRange(builder.getRange(support));
        this.itemIds = builder.getItemIds(support);
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        positionInfo.clear();
        if (!super.matches(ref, role, dt, store)) {
            return false;
        }
        Vector3d target = switch (resourceType) {
            case WATER -> ENVIRONMENT_SERVICE.findNearestWaterDrinkingPosition(ref, store, range);
            case FOOD_CONTAINER -> ENVIRONMENT_SERVICE.findNearestFoodContainerPosition(ref, store, range, itemIds);
        };
        if (target == null) {
            return false;
        }
        positionInfo.setTarget(target.x, target.y, target.z);
        return true;
    }

    @Override
    public InfoProvider getSensorInfo() {
        return infoProvider;
    }

    private static double sanitizeRange(double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            return 12.0;
        }
        return value;
    }

    private enum ResourceType {
        WATER,
        FOOD_CONTAINER;

        @Nonnull
        private static ResourceType from(@Nullable String raw) {
            if (raw == null) {
                return WATER;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("foodcontainer") || normalized.equals("food_container") || normalized.equals("food")) {
                return FOOD_CONTAINER;
            }
            return WATER;
        }
    }
}
