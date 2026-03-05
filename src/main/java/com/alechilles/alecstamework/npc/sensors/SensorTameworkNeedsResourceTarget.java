package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsEnvironmentService;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfo;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfoProvider;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedsResourceTarget;
import com.hypixel.hytale.component.ComponentType;
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
        TwNeedsConfig needsConfig = resolveNeedsConfig(ref, store);
        Vector3d target = switch (resourceType) {
            case WATER -> resolveWaterTarget(ref, store, needsConfig);
            case FOOD_CONTAINER -> resolveFoodTarget(ref, store, needsConfig);
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

    @Nullable
    private Vector3d resolveWaterTarget(@Nonnull Ref<EntityStore> ref,
                                        @Nonnull Store<EntityStore> store,
                                        @Nullable TwNeedsConfig needsConfig) {
        if (needsConfig == null) {
            return ENVIRONMENT_SERVICE.findNearestWaterDrinkingPosition(ref, store, range);
        }
        TwNeedsConfig.PassiveRefillSettings passiveRefill = needsConfig.getPassiveRefill();
        int verticalScanRadius = passiveRefill.getWaterVerticalScanRadius();
        Vector3d target = ENVIRONMENT_SERVICE.findNearestWaterDrinkingPosition(
                ref,
                store,
                range,
                verticalScanRadius
        );
        if (target != null) {
            return target;
        }
        double fallbackRange = passiveRefill.getWaterSearchRadius();
        if (fallbackRange <= 0.0 || approximatelyEqual(fallbackRange, range)) {
            return null;
        }
        return ENVIRONMENT_SERVICE.findNearestWaterDrinkingPosition(
                ref,
                store,
                fallbackRange,
                verticalScanRadius
        );
    }

    @Nullable
    private Vector3d resolveFoodTarget(@Nonnull Ref<EntityStore> ref,
                                       @Nonnull Store<EntityStore> store,
                                       @Nullable TwNeedsConfig needsConfig) {
        String[] effectiveItemIds = resolveFoodItemIds(needsConfig);
        if (effectiveItemIds == null || effectiveItemIds.length == 0) {
            return null;
        }
        if (needsConfig == null) {
            return ENVIRONMENT_SERVICE.findNearestFoodContainerPosition(ref, store, range, effectiveItemIds);
        }
        TwNeedsConfig.PassiveRefillSettings passiveRefill = needsConfig.getPassiveRefill();
        int verticalScanRadius = passiveRefill.getContainerVerticalScanRadius();
        Vector3d target = ENVIRONMENT_SERVICE.findNearestFoodContainerPosition(
                ref,
                store,
                range,
                effectiveItemIds,
                verticalScanRadius
        );
        if (target != null) {
            return target;
        }
        double fallbackRange = passiveRefill.getContainerSearchRadius();
        if (fallbackRange <= 0.0 || approximatelyEqual(fallbackRange, range)) {
            return null;
        }
        return ENVIRONMENT_SERVICE.findNearestFoodContainerPosition(
                ref,
                store,
                fallbackRange,
                effectiveItemIds,
                verticalScanRadius
        );
    }

    @Nullable
    private String[] resolveFoodItemIds(@Nullable TwNeedsConfig needsConfig) {
        if (hasAnyItemId(itemIds)) {
            return itemIds;
        }
        if (needsConfig == null) {
            return itemIds;
        }
        return needsConfig.getPassiveRefill().getContainerFoodItemIds();
    }

    private static boolean hasAnyItemId(@Nullable String[] ids) {
        if (ids == null || ids.length == 0) {
            return false;
        }
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static TwNeedsConfig resolveNeedsConfig(@Nonnull Ref<EntityStore> ref,
                                                    @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType != null) {
            TameworkNeedsComponent needs = store.getComponent(ref, needsType);
            if (needs != null) {
                String configId = needs.getConfigId();
                if (configId != null && !configId.isBlank()) {
                    TwNeedsConfig byId = TwNeedsConfig.resolveById(configId);
                    if (byId != null) {
                        return byId;
                    }
                }
            }
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(ref, store);
        return TwNeedsConfig.resolveForRole(roleId);
    }

    private static boolean approximatelyEqual(double left, double right) {
        return Math.abs(left - right) <= 0.000001;
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
