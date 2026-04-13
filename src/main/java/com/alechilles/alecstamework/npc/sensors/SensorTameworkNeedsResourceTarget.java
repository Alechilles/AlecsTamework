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
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sensor that resolves and exposes a nearby needs-resource target position.
 */
public final class SensorTameworkNeedsResourceTarget extends TameworkSensorBase {
    private static final CompanionNeedsEnvironmentService ENVIRONMENT_SERVICE = new CompanionNeedsEnvironmentService();
    private static final long TARGET_CACHE_HIT_TTL_MS = 1_500L;
    private static final long TARGET_CACHE_MISS_TTL_MS = 500L;
    private static final double EPSILON = 0.000001;

    private final ResourceType resourceType;
    @Nullable
    private final NeedType gatedNeed;
    private final double gatedRatioBelow;
    private final double range;
    private final String[] itemIds;
    private final TameworkTargetPositionInfo positionInfo = new TameworkTargetPositionInfo();
    private final TameworkTargetPositionInfoProvider infoProvider =
            new TameworkTargetPositionInfoProvider(null, positionInfo);
    private final ConcurrentHashMap<UUID, CachedTargetResult> cachedTargetsByNpcId = new ConcurrentHashMap<>();

    public SensorTameworkNeedsResourceTarget(@Nonnull BuilderSensorTameworkNeedsResourceTarget builder,
                                             @Nonnull BuilderSupport support) {
        super(builder);
        this.resourceType = ResourceType.from(builder.getResourceType(support));
        this.gatedNeed = NeedType.from(builder.getNeed(support));
        this.gatedRatioBelow = clamp01(builder.getRatioBelow(support));
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
        if (!shouldSearchForResource(ref, store)) {
            return false;
        }
        long nowMs = resolveCurrentTimeMs(store);
        UUID npcUuid = resolveNpcUuid(ref, store);
        CachedTargetResult cached = npcUuid != null ? getCachedTarget(npcUuid, nowMs) : null;
        if (cached != null) {
            if (cached.target() == null) {
                return false;
            }
            positionInfo.setTarget(cached.target().x, cached.target().y, cached.target().z);
            return true;
        }
        TwNeedsConfig needsConfig = resolveNeedsConfig(ref, store);
        Vector3d target = switch (resourceType) {
            case WATER -> resolveWaterTarget(ref, store, needsConfig);
            case FOOD_CONTAINER -> resolveFoodTarget(ref, store, needsConfig);
        };
        if (npcUuid != null) {
            cacheTarget(npcUuid, target, nowMs);
        }
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
        double consumeRadius = passiveRefill.getWaterConsumeRadius();
        Vector3d target = ENVIRONMENT_SERVICE.findNearestWaterDrinkingPosition(
                ref,
                store,
                range,
                verticalScanRadius,
                consumeRadius
        );
        if (target != null) {
            return target;
        }
        if (ENVIRONMENT_SERVICE.isNearWater(ref, store, needsConfig)) {
            return resolveCurrentPosition(ref, store);
        }
        double fallbackRange = passiveRefill.getWaterSearchRadius();
        if (fallbackRange <= 0.0 || approximatelyEqual(fallbackRange, range)) {
            return null;
        }
        target = ENVIRONMENT_SERVICE.findNearestWaterDrinkingPosition(
                ref,
                store,
                fallbackRange,
                verticalScanRadius,
                consumeRadius
        );
        if (target != null) {
            return target;
        }
        if (ENVIRONMENT_SERVICE.isNearWater(ref, store, needsConfig)) {
            return resolveCurrentPosition(ref, store);
        }
        return null;
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
        if (needsConfig == null) {
            return itemIds;
        }
        return mergeItemIds(itemIds, needsConfig.getPassiveRefill().getContainerFoodItemIds());
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

    @Nonnull
    private static String[] mergeItemIds(@Nullable String[] primary, @Nullable String[] secondary) {
        if (!hasAnyItemId(primary)) {
            return hasAnyItemId(secondary) ? secondary : new String[0];
        }
        if (!hasAnyItemId(secondary)) {
            return primary;
        }
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        appendItemIds(merged, primary);
        appendItemIds(merged, secondary);
        return merged.values().toArray(new String[0]);
    }

    private static void appendItemIds(@Nonnull LinkedHashMap<String, String> merged, @Nullable String[] itemIds) {
        if (itemIds == null || itemIds.length == 0) {
            return;
        }
        for (String itemId : itemIds) {
            if (itemId == null || itemId.isBlank()) {
                continue;
            }
            String normalized = itemId.trim().toLowerCase(Locale.ROOT);
            merged.putIfAbsent(normalized, itemId.trim());
        }
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

    @Nullable
    private static Vector3d resolveCurrentPosition(@Nonnull Ref<EntityStore> ref,
                                                   @Nonnull Store<EntityStore> store) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return null;
        }
        return new Vector3d(transform.getPosition());
    }

    private boolean shouldSearchForResource(@Nonnull Ref<EntityStore> ref,
                                            @Nonnull Store<EntityStore> store) {
        if (gatedNeed == null || !Double.isFinite(gatedRatioBelow)) {
            return true;
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return false;
        }
        TameworkNeedsComponent needs = store.getComponent(ref, needsType);
        if (needs == null) {
            return false;
        }
        TwNeedsConfig needsConfig = resolveNeedsConfig(ref, store);
        if (needsConfig == null || !needsConfig.isEnabled()) {
            return false;
        }
        TwNeedsConfig.ValueSettings values = needsConfig.getValues();
        double min = gatedNeed == NeedType.THIRST ? values.getThirstMin() : values.getHungerMin();
        double max = gatedNeed == NeedType.THIRST ? values.getThirstMax() : values.getHungerMax();
        double current = gatedNeed == NeedType.THIRST ? needs.getThirst() : needs.getHunger();
        return resolveRatio(current, min, max) <= gatedRatioBelow + EPSILON;
    }

    private static double resolveRatio(double value, double min, double max) {
        double range = max - min;
        if (!Double.isFinite(range) || range <= 0.0) {
            return 1.0;
        }
        double clamped = clamp(value, min, max);
        return clamp01((clamped - min) / range);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    @Nullable
    private CachedTargetResult getCachedTarget(@Nonnull UUID npcUuid, long nowMs) {
        CachedTargetResult cached = cachedTargetsByNpcId.get(npcUuid);
        if (cached == null) {
            return null;
        }
        if (nowMs >= cached.expiresAtMs()) {
            cachedTargetsByNpcId.remove(npcUuid, cached);
            return null;
        }
        return cached;
    }

    private void cacheTarget(@Nonnull UUID npcUuid, @Nullable Vector3d target, long nowMs) {
        long ttlMs = target != null ? TARGET_CACHE_HIT_TTL_MS : TARGET_CACHE_MISS_TTL_MS;
        cachedTargetsByNpcId.put(
                npcUuid,
                new CachedTargetResult(target != null ? new Vector3d(target) : null, nowMs + ttlMs)
        );
    }

    private long resolveCurrentTimeMs(@Nonnull Store<EntityStore> store) {
        WorldTimeResource worldTime = store.getResource(WorldTimeResource.getResourceType());
        return worldTime != null ? worldTime.getGameTime().toEpochMilli() : System.currentTimeMillis();
    }

    @Nullable
    private UUID resolveNpcUuid(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        return npc != null ? npc.getUuid() : null;
    }

    private record CachedTargetResult(@Nullable Vector3d target,
                                      long expiresAtMs) {
    }

    private enum NeedType {
        HUNGER,
        THIRST;

        @Nullable
        private static NeedType from(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "thirst" -> THIRST;
                case "hunger" -> HUNGER;
                default -> null;
            };
        }
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
