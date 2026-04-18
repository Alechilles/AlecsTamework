package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsEnvironmentService;
import com.alechilles.alecstamework.npc.progression.NeedsSeekDiagnostics;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfo;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfoProvider;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedsResourceTarget;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
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
        String npcId = resolveNpcId(ref, store);
        String roleId = CompanionRoleIdResolver.resolveRoleId(ref, store);
        if (!super.matches(ref, role, dt, store)) {
            maybeLog(ref, store, npcId, roleId, resolveResourceLabel(), "blocked", "base_sensor_mismatch", false, null, null);
            return false;
        }
        SearchEligibility eligibility = resolveSearchEligibility(ref, store);
        if (!eligibility.allowed()) {
            maybeLog(
                    ref,
                    store,
                    npcId,
                    roleId,
                    resolveResourceLabel(),
                    "blocked",
                    eligibility.reason(),
                    false,
                    eligibility.currentRatio(),
                    null
            );
            return false;
        }
        long nowMs = resolveCurrentTimeMs();
        UUID npcUuid = resolveNpcUuid(ref, store);
        CachedTargetResult cached = npcUuid != null ? getCachedTarget(npcUuid, nowMs) : null;
        if (cached != null) {
            if (cached.target() == null) {
                maybeLog(
                        ref,
                        store,
                    npcId,
                    roleId,
                    resolveResourceLabel(),
                    "miss",
                    cached.reason(),
                    true,
                    eligibility.currentRatio(),
                    null
                );
                return false;
            }
            positionInfo.setTarget(cached.target().x, cached.target().y, cached.target().z);
            maybeLog(
                    ref,
                    store,
                    npcId,
                    roleId,
                    resolveResourceLabel(),
                    "target_found",
                    "cached_target",
                    true,
                    eligibility.currentRatio(),
                    cached.target()
            );
            return true;
        }
        TwNeedsConfig needsConfig = resolveNeedsConfig(ref, store);
        TargetResolution resolution = switch (resourceType) {
            case WATER -> resolveWaterTarget(ref, store, needsConfig);
            case FOOD_CONTAINER -> resolveFoodTarget(ref, store, needsConfig);
        };
        Vector3d target = resolution.target();
        if (npcUuid != null) {
            cacheTarget(npcUuid, target, resolution.reason(), nowMs);
        }
        if (target == null) {
            maybeLog(
                    ref,
                    store,
                    npcId,
                    roleId,
                    resolveResourceLabel(),
                    "miss",
                    resolution.reason(),
                    false,
                    eligibility.currentRatio(),
                    null
            );
            return false;
        }
        positionInfo.setTarget(target.x, target.y, target.z);
        maybeLog(
                ref,
                store,
                npcId,
                roleId,
                resolveResourceLabel(),
                "target_found",
                resolution.reason(),
                false,
                eligibility.currentRatio(),
                target
        );
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
    private TargetResolution resolveWaterTarget(@Nonnull Ref<EntityStore> ref,
                                                @Nonnull Store<EntityStore> store,
                                                @Nullable TwNeedsConfig needsConfig) {
        if (needsConfig == null) {
            return TargetResolution.of(
                    ENVIRONMENT_SERVICE.findNearestWaterDrinkingPosition(ref, store, range),
                    "water_target_search_default"
            );
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
            return TargetResolution.of(target, "water_target_search_primary");
        }
        if (ENVIRONMENT_SERVICE.isNearWater(ref, store, needsConfig)) {
            return TargetResolution.of(resolveCurrentPosition(ref, store), "water_already_in_consume_range");
        }
        if (ENVIRONMENT_SERVICE.hasConsumableWaterSourceInRange(ref, store, range, verticalScanRadius)) {
            return TargetResolution.none("water_source_found_but_no_stand_target");
        }
        double fallbackRange = passiveRefill.getWaterSearchRadius();
        if (fallbackRange <= 0.0 || approximatelyEqual(fallbackRange, range)) {
            return TargetResolution.none("water_target_not_found");
        }
        target = ENVIRONMENT_SERVICE.findNearestWaterDrinkingPosition(
                ref,
                store,
                fallbackRange,
                verticalScanRadius,
                consumeRadius
        );
        if (target != null) {
            return TargetResolution.of(target, "water_target_search_fallback");
        }
        if (ENVIRONMENT_SERVICE.isNearWater(ref, store, needsConfig)) {
            return TargetResolution.of(resolveCurrentPosition(ref, store), "water_in_consume_range_after_fallback");
        }
        if (ENVIRONMENT_SERVICE.hasConsumableWaterSourceInRange(ref, store, fallbackRange, verticalScanRadius)) {
            return TargetResolution.none("water_source_found_but_no_stand_target_fallback");
        }
        return TargetResolution.none("water_target_not_found");
    }

    @Nullable
    private TargetResolution resolveFoodTarget(@Nonnull Ref<EntityStore> ref,
                                               @Nonnull Store<EntityStore> store,
                                               @Nullable TwNeedsConfig needsConfig) {
        String[] effectiveItemIds = resolveFoodItemIds(needsConfig);
        if (effectiveItemIds == null || effectiveItemIds.length == 0) {
            return TargetResolution.none("food_item_ids_empty");
        }
        if (needsConfig == null) {
            return TargetResolution.of(
                    ENVIRONMENT_SERVICE.findNearestFoodContainerPosition(ref, store, range, effectiveItemIds),
                    "food_target_search_default"
            );
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
            return TargetResolution.of(target, "food_target_search_primary");
        }
        if (ENVIRONMENT_SERVICE.hasFoodContainerWithAllowedFoodInRange(ref, store, range, effectiveItemIds, verticalScanRadius)) {
            return TargetResolution.none("food_source_found_but_no_stand_target");
        }
        double fallbackRange = passiveRefill.getContainerSearchRadius();
        if (fallbackRange <= 0.0 || approximatelyEqual(fallbackRange, range)) {
            return TargetResolution.none("food_target_not_found");
        }
        Vector3d fallbackTarget = ENVIRONMENT_SERVICE.findNearestFoodContainerPosition(
                ref,
                store,
                fallbackRange,
                effectiveItemIds,
                verticalScanRadius
        );
        if (fallbackTarget != null) {
            return TargetResolution.of(fallbackTarget, "food_target_search_fallback");
        }
        if (ENVIRONMENT_SERVICE.hasFoodContainerWithAllowedFoodInRange(ref, store, fallbackRange, effectiveItemIds, verticalScanRadius)) {
            return TargetResolution.none("food_source_found_but_no_stand_target_fallback");
        }
        return TargetResolution.none("food_target_not_found");
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

    private SearchEligibility resolveSearchEligibility(@Nonnull Ref<EntityStore> ref,
                                                       @Nonnull Store<EntityStore> store) {
        if (gatedNeed == null || !Double.isFinite(gatedRatioBelow)) {
            return SearchEligibility.allowed(Double.NaN);
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return SearchEligibility.blocked("needs_component_type_missing", Double.NaN);
        }
        TameworkNeedsComponent needs = store.getComponent(ref, needsType);
        if (needs == null) {
            return SearchEligibility.blocked("needs_component_missing", Double.NaN);
        }
        TwNeedsConfig needsConfig = resolveNeedsConfig(ref, store);
        if (needsConfig == null || !needsConfig.isEnabled()) {
            return SearchEligibility.blocked("needs_config_missing_or_disabled", Double.NaN);
        }
        TwNeedsConfig.ValueSettings values = needsConfig.getValues();
        double min = gatedNeed == NeedType.THIRST ? values.getThirstMin() : values.getHungerMin();
        double max = gatedNeed == NeedType.THIRST ? values.getThirstMax() : values.getHungerMax();
        double current = gatedNeed == NeedType.THIRST ? needs.getThirst() : needs.getHunger();
        double currentRatio = resolveRatio(current, min, max);
        if (currentRatio <= gatedRatioBelow + EPSILON) {
            return SearchEligibility.allowed(currentRatio);
        }
        return SearchEligibility.blocked("need_ratio_above_threshold", currentRatio);
    }

    @Nonnull
    private String resolveResourceLabel() {
        return resourceType == ResourceType.FOOD_CONTAINER ? "FoodContainer" : "Water";
    }

    private void maybeLog(@Nonnull Ref<EntityStore> ref,
                          @Nonnull Store<EntityStore> store,
                          @Nonnull String npcId,
                          @Nullable String roleId,
                          @Nonnull String resourceLabel,
                          @Nonnull String result,
                          @Nonnull String detail,
                          boolean cacheHit,
                          @Nullable Double currentRatio,
                          @Nullable Vector3d target) {
        NeedsSeekDiagnostics.maybeLog(
                npcId,
                roleId,
                resourceLabel,
                result,
                detail,
                range,
                currentRatio,
                gatedNeed == null || !Double.isFinite(gatedRatioBelow) ? null : gatedRatioBelow,
                cacheHit,
                target
        );
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

    private void cacheTarget(@Nonnull UUID npcUuid,
                             @Nullable Vector3d target,
                             @Nonnull String reason,
                             long nowMs) {
        long ttlMs = target != null ? TARGET_CACHE_HIT_TTL_MS : TARGET_CACHE_MISS_TTL_MS;
        cachedTargetsByNpcId.put(
                npcUuid,
                new CachedTargetResult(target != null ? new Vector3d(target) : null, reason, nowMs + ttlMs)
        );
    }

    private long resolveCurrentTimeMs() {
        return System.currentTimeMillis();
    }

    @Nonnull
    private static String resolveNpcId(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        if (npc != null && npc.getUuid() != null) {
            return npc.getUuid().toString();
        }
        return ref.toString();
    }

    @Nullable
    private UUID resolveNpcUuid(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        return npc != null ? npc.getUuid() : null;
    }

    private record CachedTargetResult(@Nullable Vector3d target,
                                      @Nonnull String reason,
                                      long expiresAtMs) {
    }

    private record SearchEligibility(boolean allowed,
                                     @Nonnull String reason,
                                     double currentRatio) {
        @Nonnull
        private static SearchEligibility allowed(double currentRatio) {
            return new SearchEligibility(true, "eligible", currentRatio);
        }

        @Nonnull
        private static SearchEligibility blocked(@Nonnull String reason, double currentRatio) {
            return new SearchEligibility(false, reason, currentRatio);
        }
    }

    private record TargetResolution(@Nullable Vector3d target,
                                    @Nonnull String reason) {
        @Nonnull
        private static TargetResolution of(@Nullable Vector3d target, @Nonnull String reason) {
            if (target == null) {
                return new TargetResolution(null, reason + "_miss");
            }
            return new TargetResolution(target, reason);
        }

        @Nonnull
        private static TargetResolution none(@Nonnull String reason) {
            return new TargetResolution(null, reason);
        }
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
