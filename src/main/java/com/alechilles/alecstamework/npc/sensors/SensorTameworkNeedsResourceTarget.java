package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsEnvironmentService;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsEnvironmentService.TargetRejector;
import com.alechilles.alecstamework.npc.progression.CompanionNeedsEnvironmentService.WaterTargetSearchResult;
import com.alechilles.alecstamework.npc.progression.NeedsResourcePathPreflightService;
import com.alechilles.alecstamework.npc.progression.NeedsResourcePathPreflightService.PathPreflightResult;
import com.alechilles.alecstamework.npc.progression.NeedsSeekDiagnostics;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfo;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfoProvider;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedsResourceTarget;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final NeedsResourcePathPreflightService PATH_PREFLIGHT_SERVICE = new NeedsResourcePathPreflightService();
    private static final long TARGET_CACHE_HIT_TTL_MS = 1_500L;
    private static final long TARGET_CACHE_MISS_TTL_MS = 3_000L;
    private static final double REJECTED_TARGET_DEFAULT_TTL_SECONDS = 30.0;
    private static final int REJECTED_TARGET_MAX_ENTRIES = 4096;
    private static final double EPSILON = 0.000001;
    private static final ConcurrentHashMap<RejectedTargetKey, Long> rejectedTargets = new ConcurrentHashMap<>();

    private final ResourceType resourceType;
    @Nullable
    private final NeedType gatedNeed;
    private final double gatedRatioBelow;
    private final double range;
    private final String[] itemIds;
    private final boolean hasConfiguredItemIds;
    private final TameworkTargetPositionInfo positionInfo = new TameworkTargetPositionInfo();
    private final TameworkTargetPositionInfoProvider infoProvider =
            new TameworkTargetPositionInfoProvider(null, positionInfo);
    private final ConcurrentHashMap<UUID, CachedTargetResult> cachedTargetsByNpcId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<FoodItemIdsCacheKey, String[]> foodItemIdsByConfig = new ConcurrentHashMap<>();

    public SensorTameworkNeedsResourceTarget(@Nonnull BuilderSensorTameworkNeedsResourceTarget builder,
                                             @Nonnull BuilderSupport support) {
        super(builder);
        this.resourceType = ResourceType.from(builder.getResourceType(support));
        this.gatedNeed = NeedType.from(builder.getNeed(support));
        this.gatedRatioBelow = clamp01(builder.getRatioBelow(support));
        this.range = sanitizeRange(builder.getRange(support));
        this.itemIds = sanitizeItemIds(builder.getItemIds(support));
        this.hasConfiguredItemIds = this.itemIds.length > 0;
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
            if (cached.target() != null && npcUuid != null && isTargetRejected(npcUuid, resourceType, cached.target(), nowMs)) {
                cachedTargetsByNpcId.remove(npcUuid, cached);
                cached = null;
            }
        }
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
        TwNeedsConfig needsConfig = eligibility.needsConfig();
        if (needsConfig == null) {
            needsConfig = resolveNeedsConfig(ref, store);
        }
        TargetResolution resolution = switch (resourceType) {
            case WATER -> resolveWaterTarget(ref, role, store, needsConfig, npcUuid, nowMs);
            case FOOD_CONTAINER -> resolveFoodTarget(ref, role, store, needsConfig, npcUuid, nowMs);
        };
        Vector3d target = resolution.target();
        if (target == null) {
            if (npcUuid != null) {
                cacheTarget(npcUuid, null, resolution.reason(), nowMs);
            }
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
        if (npcUuid == null) {
            maybeLog(
                    ref,
                    store,
                    npcId,
                    roleId,
                    resolveResourceLabel(),
                    "miss",
                    "path_preflight_npc_uuid_missing",
                    false,
                    eligibility.currentRatio(),
                    target
            );
            return false;
        }
        PathPreflightResult preflight = PATH_PREFLIGHT_SERVICE.preflight(
                ref,
                role,
                store,
                npcUuid,
                resolveResourceLabel(),
                target,
                nowMs
        );
        if (!preflight.ready()) {
            if (preflight.noPath()) {
                rejectTarget(npcUuid, resourceType, target, REJECTED_TARGET_DEFAULT_TTL_SECONDS, nowMs);
            }
            maybeLog(
                    ref,
                    store,
                    npcId,
                    roleId,
                    resolveResourceLabel(),
                    "miss",
                    preflight.reason(),
                    false,
                    eligibility.currentRatio(),
                    target
            );
            return false;
        }
        if (npcUuid != null) {
            cacheTarget(npcUuid, target, resolution.reason(), nowMs);
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
                                                @Nonnull Role role,
                                                @Nonnull Store<EntityStore> store,
                                                @Nullable TwNeedsConfig needsConfig,
                                                @Nullable UUID npcUuid,
                                                long nowMs) {
        TargetRejector targetRejector = createTargetRejector(npcUuid, ResourceType.WATER, nowMs);
        if (needsConfig == null) {
            return TargetResolution.of(
                    ENVIRONMENT_SERVICE.findNearestWaterDrinkingPosition(ref, store, range),
                    "water_target_search_default"
            );
        }
        TwNeedsConfig.PassiveRefillSettings passiveRefill = needsConfig.getPassiveRefill();
        int verticalScanRadius = passiveRefill.getWaterVerticalScanRadius();
        double consumeRadius = passiveRefill.getWaterConsumeRadius();
        WaterTargetSearchResult primaryResult = ENVIRONMENT_SERVICE.findNearestWaterDrinkingTarget(
                ref,
                role,
                store,
                range,
                verticalScanRadius,
                consumeRadius,
                targetRejector
        );
        TargetResolution primaryResolution = resolveWaterSearchResult(
                primaryResult,
                "water_target_search_primary",
                "water_already_in_consume_range",
                "water_source_found_but_no_stand_target",
                ref,
                store
        );
        if (primaryResolution.isResolved()) {
            return primaryResolution;
        }
        double fallbackRange = passiveRefill.getWaterSearchRadius();
        if (!shouldRunFallbackWaterSearch(range, fallbackRange)) {
            return TargetResolution.none("water_target_not_found");
        }
        WaterTargetSearchResult fallbackResult = ENVIRONMENT_SERVICE.findNearestWaterDrinkingTarget(
                ref,
                role,
                store,
                fallbackRange,
                verticalScanRadius,
                consumeRadius,
                targetRejector
        );
        TargetResolution fallbackResolution = resolveWaterSearchResult(
                fallbackResult,
                "water_target_search_fallback",
                "water_in_consume_range_after_fallback",
                "water_source_found_but_no_stand_target_fallback",
                ref,
                store
        );
        if (fallbackResolution.isResolved()) {
            return fallbackResolution;
        }
        return TargetResolution.none("water_target_not_found");
    }

    @Nonnull
    private TargetResolution resolveWaterSearchResult(@Nonnull WaterTargetSearchResult result,
                                                      @Nonnull String targetReason,
                                                      @Nonnull String consumeRangeReason,
                                                      @Nonnull String noStandReason,
                                                      @Nonnull Ref<EntityStore> ref,
                                                      @Nonnull Store<EntityStore> store) {
        if (result.target() != null) {
            return TargetResolution.of(result.target(), targetReason);
        }
        if (result.foundConsumableSourceInConsumeRange()) {
            return TargetResolution.of(resolveCurrentPosition(ref, store), consumeRangeReason);
        }
        if (result.foundConsumableSource()) {
            return TargetResolution.none(noStandReason);
        }
        return TargetResolution.unresolved();
    }

    @Nullable
    private TargetResolution resolveFoodTarget(@Nonnull Ref<EntityStore> ref,
                                               @Nonnull Role role,
                                               @Nonnull Store<EntityStore> store,
                                               @Nullable TwNeedsConfig needsConfig,
                                               @Nullable UUID npcUuid,
                                               long nowMs) {
        String[] effectiveItemIds = resolveFoodItemIds(needsConfig);
        if (effectiveItemIds == null || effectiveItemIds.length == 0) {
            return TargetResolution.none("food_item_ids_empty");
        }
        TargetRejector targetRejector = createTargetRejector(npcUuid, ResourceType.FOOD_CONTAINER, nowMs);
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
                role,
                store,
                range,
                effectiveItemIds,
                verticalScanRadius,
                targetRejector
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
                role,
                store,
                fallbackRange,
                effectiveItemIds,
                verticalScanRadius,
                targetRejector
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
    String[] resolveFoodItemIds(@Nullable TwNeedsConfig needsConfig) {
        if (needsConfig == null) {
            return itemIds;
        }
        String[] passiveItemIds = needsConfig.getPassiveRefill().getContainerFoodItemIds();
        String[] sanitizedPassiveItemIds = sanitizeItemIds(passiveItemIds);
        if (hasConfiguredItemIds && sanitizedPassiveItemIds.length == 0) {
            return itemIds;
        }
        FoodItemIdsCacheKey key = FoodItemIdsCacheKey.from(needsConfig, sanitizedPassiveItemIds, hasConfiguredItemIds);
        return foodItemIdsByConfig.computeIfAbsent(key, ignored -> hasConfiguredItemIds
                ? mergeItemIds(itemIds, sanitizedPassiveItemIds)
                : sanitizedPassiveItemIds);
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
    static String[] mergeItemIds(@Nullable String[] primary, @Nullable String[] secondary) {
        if (!hasAnyItemId(primary)) {
            return sanitizeItemIds(secondary);
        }
        if (!hasAnyItemId(secondary)) {
            return sanitizeItemIds(primary);
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

    @Nonnull
    private static String[] sanitizeItemIds(@Nullable String[] ids) {
        if (!hasAnyItemId(ids)) {
            return new String[0];
        }
        LinkedHashMap<String, String> sanitized = new LinkedHashMap<>();
        appendItemIds(sanitized, ids);
        return sanitized.values().toArray(new String[0]);
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
            return SearchEligibility.allowed(Double.NaN, null);
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return SearchEligibility.blocked("needs_component_type_missing", Double.NaN, null);
        }
        TameworkNeedsComponent needs = store.getComponent(ref, needsType);
        if (needs == null) {
            return SearchEligibility.blocked("needs_component_missing", Double.NaN, null);
        }
        TwNeedsConfig needsConfig = resolveNeedsConfig(ref, store);
        if (needsConfig == null || !TameworkRuntimeSettings.needsEnabled(needsConfig.isEnabled())) {
            return SearchEligibility.blocked("needs_config_missing_or_disabled", Double.NaN, needsConfig);
        }
        TwNeedsConfig.ValueSettings values = needsConfig.getValues();
        double min = gatedNeed == NeedType.THIRST ? values.getThirstMin() : values.getHungerMin();
        double max = gatedNeed == NeedType.THIRST ? values.getThirstMax() : values.getHungerMax();
        double current = gatedNeed == NeedType.THIRST ? needs.getThirst() : needs.getHunger();
        double currentRatio = resolveRatio(current, min, max);
        if (currentRatio <= gatedRatioBelow + EPSILON) {
            return SearchEligibility.allowed(currentRatio, needsConfig);
        }
        return SearchEligibility.blocked("need_ratio_above_threshold", currentRatio, needsConfig);
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
        long ttlMs = targetCacheTtlMs(target != null);
        cachedTargetsByNpcId.put(
                npcUuid,
                new CachedTargetResult(target != null ? new Vector3d(target) : null, reason, nowMs + ttlMs)
        );
    }

    static boolean shouldRunFallbackWaterSearch(double primaryRange, double fallbackRange) {
        return Double.isFinite(fallbackRange)
                && fallbackRange > 0.0
                && (!Double.isFinite(primaryRange) || fallbackRange > primaryRange + EPSILON);
    }

    static long targetCacheTtlMs(boolean hasTarget) {
        return hasTarget ? TARGET_CACHE_HIT_TTL_MS : TARGET_CACHE_MISS_TTL_MS;
    }

    public static boolean rejectTarget(@Nullable UUID npcUuid,
                                       @Nullable String rawResourceType,
                                       @Nullable Vector3d target,
                                       double suppressSeconds) {
        long nowMs = System.currentTimeMillis();
        if (isAutoResourceType(rawResourceType)) {
            boolean waterRejected = rejectTarget(npcUuid, ResourceType.WATER, target, suppressSeconds, nowMs);
            boolean foodRejected = rejectTarget(
                    npcUuid,
                    ResourceType.FOOD_CONTAINER,
                    target,
                    suppressSeconds,
                    nowMs
            );
            return waterRejected || foodRejected;
        }
        ResourceType type = ResourceType.from(rawResourceType);
        return rejectTarget(npcUuid, type, target, suppressSeconds, nowMs);
    }

    static boolean rejectTarget(@Nullable UUID npcUuid,
                                @Nonnull ResourceType type,
                                @Nullable Vector3d target,
                                double suppressSeconds,
                                long nowMs) {
        if (npcUuid == null || target == null || !isFinite(target)) {
            return false;
        }
        double ttlSeconds = Double.isFinite(suppressSeconds) && suppressSeconds > 0.0
                ? suppressSeconds
                : REJECTED_TARGET_DEFAULT_TTL_SECONDS;
        long ttlMs = Math.max(1L, (long) Math.ceil(ttlSeconds * 1000.0));
        rejectedTargets.put(RejectedTargetKey.from(npcUuid, type, target), nowMs + ttlMs);
        cleanupRejectedTargets(nowMs);
        return true;
    }

    static boolean rejectTargetForTests(@Nullable UUID npcUuid,
                                        @Nullable String rawResourceType,
                                        @Nullable Vector3d target,
                                        double suppressSeconds,
                                        long nowMs) {
        if (isAutoResourceType(rawResourceType)) {
            boolean waterRejected = rejectTarget(npcUuid, ResourceType.WATER, target, suppressSeconds, nowMs);
            boolean foodRejected = rejectTarget(npcUuid, ResourceType.FOOD_CONTAINER, target, suppressSeconds, nowMs);
            return waterRejected || foodRejected;
        }
        return rejectTarget(npcUuid, ResourceType.from(rawResourceType), target, suppressSeconds, nowMs);
    }

    static boolean isTargetRejected(@Nullable UUID npcUuid,
                                    @Nonnull ResourceType type,
                                    @Nullable Vector3d target,
                                    long nowMs) {
        if (npcUuid == null || target == null || !isFinite(target)) {
            return false;
        }
        RejectedTargetKey key = RejectedTargetKey.from(npcUuid, type, target);
        Long expiresAtMs = rejectedTargets.get(key);
        if (expiresAtMs == null) {
            return false;
        }
        if (nowMs >= expiresAtMs) {
            rejectedTargets.remove(key, expiresAtMs);
            return false;
        }
        return true;
    }

    static boolean isTargetRejectedForTests(@Nullable UUID npcUuid,
                                            @Nullable String rawResourceType,
                                            @Nullable Vector3d target,
                                            long nowMs) {
        return isTargetRejected(npcUuid, ResourceType.from(rawResourceType), target, nowMs);
    }

    @Nullable
    private static TargetRejector createTargetRejector(@Nullable UUID npcUuid,
                                                       @Nonnull ResourceType type,
                                                       long nowMs) {
        if (!hasRejectedTargetFor(npcUuid, type, nowMs)) {
            return null;
        }
        return target -> isTargetRejected(npcUuid, type, target, nowMs);
    }

    private static boolean hasRejectedTargetFor(@Nullable UUID npcUuid,
                                                @Nonnull ResourceType type,
                                                long nowMs) {
        if (npcUuid == null || rejectedTargets.isEmpty()) {
            return false;
        }
        boolean found = false;
        for (var entry : rejectedTargets.entrySet()) {
            RejectedTargetKey key = entry.getKey();
            Long expiresAtMs = entry.getValue();
            if (key == null || expiresAtMs == null || nowMs >= expiresAtMs) {
                if (key != null && expiresAtMs != null) {
                    rejectedTargets.remove(key, expiresAtMs);
                }
                continue;
            }
            if (key.npcUuid().equals(npcUuid) && key.resourceType() == type) {
                found = true;
            }
        }
        return found;
    }

    private static void cleanupRejectedTargets(long nowMs) {
        if (rejectedTargets.size() < REJECTED_TARGET_MAX_ENTRIES) {
            return;
        }
        rejectedTargets.entrySet().removeIf(entry -> entry == null
                || entry.getKey() == null
                || entry.getValue() == null
                || nowMs >= entry.getValue());
        int excess = rejectedTargets.size() - REJECTED_TARGET_MAX_ENTRIES;
        if (excess <= 0) {
            return;
        }
        for (RejectedTargetKey key : rejectedTargets.keySet()) {
            if (excess <= 0) {
                return;
            }
            if (rejectedTargets.remove(key) != null) {
                excess--;
            }
        }
    }

    static void clearRejectedTargetsForTests() {
        rejectedTargets.clear();
    }

    static int rejectedTargetCountForTests() {
        return rejectedTargets.size();
    }

    static int rejectedTargetMaxEntriesForTests() {
        return REJECTED_TARGET_MAX_ENTRIES;
    }

    private static boolean isFinite(@Nonnull Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static boolean isAutoResourceType(@Nullable String rawResourceType) {
        return rawResourceType == null
                || rawResourceType.trim().isEmpty()
                || "Auto".equalsIgnoreCase(rawResourceType.trim());
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
                                     double currentRatio,
                                     @Nullable TwNeedsConfig needsConfig) {
        @Nonnull
        private static SearchEligibility allowed(double currentRatio, @Nullable TwNeedsConfig needsConfig) {
            return new SearchEligibility(true, "eligible", currentRatio, needsConfig);
        }

        @Nonnull
        private static SearchEligibility blocked(@Nonnull String reason,
                                                 double currentRatio,
                                                 @Nullable TwNeedsConfig needsConfig) {
            return new SearchEligibility(false, reason, currentRatio, needsConfig);
        }
    }

    private record FoodItemIdsCacheKey(@Nonnull String configId,
                                       @Nonnull List<String> passiveItemIds,
                                       boolean hasConfiguredItemIds) {
        @Nonnull
        private static FoodItemIdsCacheKey from(@Nonnull TwNeedsConfig config,
                                                @Nonnull String[] passiveItemIds,
                                                boolean hasConfiguredItemIds) {
            String configId = config.getId();
            return new FoodItemIdsCacheKey(
                    configId == null || configId.isBlank() ? "<default>" : configId.trim().toLowerCase(Locale.ROOT),
                    List.of(passiveItemIds),
                    hasConfiguredItemIds
            );
        }
    }

    private record RejectedTargetKey(@Nonnull UUID npcUuid,
                                     @Nonnull ResourceType resourceType,
                                     int blockX,
                                     int blockY,
                                     int blockZ) {
        @Nonnull
        private static RejectedTargetKey from(@Nonnull UUID npcUuid,
                                              @Nonnull ResourceType resourceType,
                                              @Nonnull Vector3d target) {
            return new RejectedTargetKey(
                    npcUuid,
                    resourceType,
                    (int) Math.floor(target.x),
                    (int) Math.floor(target.y),
                    (int) Math.floor(target.z)
            );
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

        @Nonnull
        private static TargetResolution unresolved() {
            return new TargetResolution(null, "unresolved");
        }

        private boolean isResolved() {
            return target != null || !"unresolved".equals(reason);
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
