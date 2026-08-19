package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.NeedsResourceFastModePolicy;
import com.alechilles.alecstamework.npc.progression.NeedsResourcePathPreflightService;
import com.alechilles.alecstamework.npc.progression.NeedsResourcePathPreflightService.PathPreflightResult;
import com.alechilles.alecstamework.npc.progression.NeedsResourceSearchCoordinator;
import com.alechilles.alecstamework.npc.progression.NeedsSeekDiagnostics;
import com.alechilles.alecstamework.npc.progression.NeedsTelemetryDiagnostics;
import com.alechilles.alecstamework.npc.progression.PositionTargetRejectCache;
import com.alechilles.alecstamework.npc.progression.PositionTargetReservationCache;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfo;
import com.alechilles.alecstamework.npc.sensorinfo.TameworkTargetPositionInfoProvider;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkNeedsResourceTarget;
import com.alechilles.alecstamework.settings.TameworkRuntimeSettings;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Reads shared needs-resource results and exposes one selected target.
 *
 * <p>Cold chunk traversal runs in {@code NeedsResourceSearchSystem}; this
 * sensor only reads local/shared values and admits bounded work.</p>
 */
public final class SensorTameworkNeedsResourceTarget extends TameworkSensorBase {
    private static final NeedsResourcePathPreflightService PATH_PREFLIGHT_SERVICE =
            new NeedsResourcePathPreflightService();
    private static final int MAX_ACTIVE_SEEK_VERTICAL_SCAN_RADIUS = 16;
    private static final double PREFLIGHT_REJECT_TTL_SECONDS = 4.0;
    private static final double DEFAULT_APPROACH_RADIUS = 2.0;
    private static final double EPSILON = 0.000001;
    private static final SearchEligibility UNGATED_ELIGIBILITY =
            SearchEligibility.allowed(Double.NaN, null);

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
    private final NeedsResourceTargetCacheAdapter targetCache = new NeedsResourceTargetCacheAdapter();
    private final ConcurrentHashMap<FoodItemIdsCacheKey, String[]> foodItemIdsByConfig = new ConcurrentHashMap<>();

    public SensorTameworkNeedsResourceTarget(@Nonnull BuilderSensorTameworkNeedsResourceTarget builder,
                                             @Nonnull BuilderSupport support) {
        super(builder);
        resourceType = ResourceType.from(builder.getResourceType(support));
        gatedNeed = NeedType.from(builder.getNeed(support));
        gatedRatioBelow = clamp01(builder.getRatioBelow(support));
        range = sanitizeRange(builder.getRange(support));
        itemIds = sanitizeItemIds(builder.getItemIds(support));
        hasConfiguredItemIds = itemIds.length > 0;
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        positionInfo.clear();
        if (!super.matches(ref, role, dt, store)) {
            log(ref, store, "blocked", "base_sensor_mismatch", false, Double.NaN, null);
            return false;
        }
        SearchEligibility eligibility = resolveSearchEligibility(ref, store);
        if (!eligibility.allowed()) {
            log(ref, store, "blocked", eligibility.reason(), false, eligibility.currentRatio(), null);
            return false;
        }
        UUID npcUuid = resolveNpcUuid(ref, store);
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (npcUuid == null || transform == null || transform.getPosition() == null) {
            log(ref, store, "miss", "npc_position_missing", false, eligibility.currentRatio(), null);
            return false;
        }
        double originX = transform.getPosition().x;
        double originY = transform.getPosition().y;
        double originZ = transform.getPosition().z;
        String worldName = resolveWorldName(store);
        if (worldName == null) {
            log(ref, store, "miss", "world_missing", false, eligibility.currentRatio(), null);
            return false;
        }
        TwNeedsConfig needsConfig = eligibility.needsConfig();
        if (needsConfig == null) {
            needsConfig = resolveNeedsConfig(ref, store);
        }
        NeedsResourceSearchCoordinator.Request request = createRequest(
                needsConfig,
                worldName,
                originX,
                originY,
                originZ
        );
        if (request == null) {
            log(ref, store, "miss", "resource_request_invalid", false, eligibility.currentRatio(), null);
            return false;
        }
        long nowMs = System.currentTimeMillis();
        NeedsResourceTargetCacheAdapter.Result result = targetCache.resolve(
                store,
                npcUuid,
                request,
                worldName,
                originX,
                originY,
                originZ,
                request.radius(),
                request.verticalRadius(),
                NeedsResourceFastModePolicy.isFastModeActive(nowMs),
                nowMs
        );
        if (result.status() == NeedsResourceTargetCacheAdapter.Status.DEFERRED
                || result.status() == NeedsResourceTargetCacheAdapter.Status.RESERVED) {
            return false;
        }
        Vector3d target = result.target();
        if (target == null && resourceType == ResourceType.WATER
                && "resource_target_not_found".equals(result.reason())) {
            Vector3d recent = targetCache.resolveRecentTarget(
                    npcUuid,
                    new Vector3d(originX, originY, originZ),
                    nowMs
            );
            if (recent != null) {
                result = targetCache.adoptTarget(
                        npcUuid,
                        worldName,
                        resourceType.kind,
                        recent,
                        DEFAULT_APPROACH_RADIUS,
                        request.radius(),
                        request.verticalRadius(),
                        nowMs
                );
                target = result.target();
            }
        }
        if (result.status() != NeedsResourceTargetCacheAdapter.Status.TARGET || target == null) {
            log(ref, store, "miss", result.reason(), result.cacheHit(), eligibility.currentRatio(), null);
            return false;
        }
        if (!result.fastConsume()) {
            PathPreflightResult preflight = PATH_PREFLIGHT_SERVICE.preflight(
                    ref,
                    role,
                    store,
                    npcUuid,
                    resourceType.label,
                    target,
                    result.approachRadius(),
                    nowMs
            );
            logPreflight(ref, store, preflight, result.reason(), result.approachRadius(), target);
            if (!preflight.ready()) {
                if (preflight.noPath()) {
                    targetCache.invalidateCandidate(store, request, target, nowMs);
                    targetCache.forgetRecentTarget(npcUuid, target);
                    NeedsResourceTargetCacheAdapter.rejectTarget(
                            npcUuid,
                            resourceType.kind,
                            target,
                            PREFLIGHT_REJECT_TTL_SECONDS
                    );
                }
                targetCache.clearTarget(npcUuid, worldName, resourceType.kind, target);
                log(ref, store, "miss", preflight.reason(), false, eligibility.currentRatio(), target);
                return false;
            }
        }
        targetCache.rememberRecentTarget(npcUuid, target, nowMs);
        positionInfo.setTarget(target.x, target.y, target.z);
        log(ref, store, "target_found", result.reason(), result.cacheHit(), eligibility.currentRatio(), target);
        return true;
    }

    @Override
    public InfoProvider getSensorInfo() {
        return infoProvider;
    }

    @Nullable
    private NeedsResourceSearchCoordinator.Request createRequest(@Nullable TwNeedsConfig config,
                                                                 @Nonnull String worldName,
                                                                 double originX,
                                                                 double originY,
                                                                 double originZ) {
        double searchRadius = range;
        int verticalRadius = 8;
        double consumeRadius = 0.0;
        List<String> effectiveItemIds = List.of();
        if (config != null) {
            TwNeedsConfig.PassiveRefillSettings passive = config.getPassiveRefill();
            if (resourceType == ResourceType.WATER) {
                searchRadius = Math.max(searchRadius, passive.getWaterSearchRadius());
                verticalRadius = activeSeekVerticalScanRadius(passive.getWaterVerticalScanRadius(), searchRadius);
                consumeRadius = passive.getWaterConsumeRadius();
            } else {
                searchRadius = Math.max(searchRadius, passive.getContainerSearchRadius());
                verticalRadius = activeSeekVerticalScanRadius(passive.getContainerVerticalScanRadius(), searchRadius);
                consumeRadius = passive.getContainerConsumeRadius();
                String[] ids = resolveFoodItemIds(config);
                if (ids == null || ids.length == 0) {
                    return null;
                }
                effectiveItemIds = Arrays.asList(ids);
            }
        } else if (resourceType == ResourceType.FOOD_CONTAINER && !hasConfiguredItemIds) {
            return null;
        } else if (resourceType == ResourceType.FOOD_CONTAINER) {
            effectiveItemIds = Arrays.asList(itemIds);
        }
        try {
            return NeedsResourceSearchCoordinator.Request.forArea(
                    resourceType.kind,
                    worldName,
                    originX,
                    originY,
                    originZ,
                    searchRadius,
                    verticalRadius,
                    consumeRadius,
                    effectiveItemIds
            );
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    String[] resolveFoodItemIds(@Nullable TwNeedsConfig needsConfig) {
        if (needsConfig == null) {
            return itemIds;
        }
        String[] passiveItemIds = sanitizeItemIds(needsConfig.getPassiveRefill().getContainerFoodItemIds());
        if (hasConfiguredItemIds && passiveItemIds.length == 0) {
            return itemIds;
        }
        FoodItemIdsCacheKey key = FoodItemIdsCacheKey.from(needsConfig, passiveItemIds, hasConfiguredItemIds);
        return foodItemIdsByConfig.computeIfAbsent(
                key,
                ignored -> hasConfiguredItemIds ? mergeItemIds(itemIds, passiveItemIds) : passiveItemIds
        );
    }

    private SearchEligibility resolveSearchEligibility(@Nonnull Ref<EntityStore> ref,
                                                       @Nonnull Store<EntityStore> store) {
        if (gatedNeed == null) {
            return UNGATED_ELIGIBILITY;
        }
        if (!Double.isFinite(gatedRatioBelow)) {
            return SearchEligibility.allowed(Double.NaN, null);
        }
        ComponentType<EntityStore, TameworkNeedsComponent> type = TameworkNeedsComponent.getComponentType();
        if (type == null) {
            return SearchEligibility.blocked("needs_component_type_missing", Double.NaN, null);
        }
        TameworkNeedsComponent needs = store.getComponent(ref, type);
        if (needs == null) {
            return SearchEligibility.blocked("needs_component_missing", Double.NaN, null);
        }
        TwNeedsConfig config = resolveNeedsConfig(ref, store);
        if (config == null || !TameworkRuntimeSettings.needsEnabled(config.isEnabled())) {
            return SearchEligibility.blocked("needs_config_missing_or_disabled", Double.NaN, config);
        }
        TwNeedsConfig.ValueSettings values = config.getValues();
        double min = gatedNeed == NeedType.THIRST ? values.getThirstMin() : values.getHungerMin();
        double max = gatedNeed == NeedType.THIRST ? values.getThirstMax() : values.getHungerMax();
        double current = gatedNeed == NeedType.THIRST ? needs.getThirst() : needs.getHunger();
        double ratio = resolveRatio(current, min, max);
        return ratio <= gatedRatioBelow + EPSILON
                ? SearchEligibility.allowed(ratio, config)
                : SearchEligibility.blocked("need_ratio_above_threshold", ratio, config);
    }

    @Nullable
    private static TwNeedsConfig resolveNeedsConfig(@Nonnull Ref<EntityStore> ref,
                                                    @Nonnull Store<EntityStore> store) {
        ComponentType<EntityStore, TameworkNeedsComponent> type = TameworkNeedsComponent.getComponentType();
        if (type != null) {
            TameworkNeedsComponent needs = store.getComponent(ref, type);
            if (needs != null && needs.getConfigId() != null && !needs.getConfigId().isBlank()) {
                TwNeedsConfig resolved = TwNeedsConfig.resolveById(needs.getConfigId());
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return TwNeedsConfig.resolveForRole(CompanionRoleIdResolver.resolveRoleId(ref, store));
    }

    private void log(@Nonnull Ref<EntityStore> ref,
                     @Nonnull Store<EntityStore> store,
                     @Nonnull String result,
                     @Nonnull String detail,
                     boolean cacheHit,
                     double currentRatio,
                     @Nullable Vector3d target) {
        if (!diagnosticsEnabled()) {
            return;
        }
        Double diagnosticRatio = Double.isFinite(currentRatio) ? currentRatio : null;
        NeedsSeekDiagnostics.maybeLog(
                resolveNpcId(ref, store),
                CompanionRoleIdResolver.resolveRoleId(ref, store),
                resourceType.label,
                result,
                detail,
                range,
                diagnosticRatio,
                gatedNeed == null ? null : gatedRatioBelow,
                cacheHit,
                target,
                resolveCurrentPosition(ref, store)
        );
    }

    private void logPreflight(@Nonnull Ref<EntityStore> ref,
                              @Nonnull Store<EntityStore> store,
                              @Nonnull PathPreflightResult preflight,
                              @Nonnull String sourceReason,
                              double approachRadius,
                              @Nonnull Vector3d target) {
        if (!NeedsSeekDiagnostics.isEnabled()) {
            return;
        }
        NeedsSeekDiagnostics.maybeLogPreflight(
                resolveNpcId(ref, store),
                CompanionRoleIdResolver.resolveRoleId(ref, store),
                resourceType.label,
                preflight.status().name(),
                preflight.reason(),
                sourceReason,
                approachRadius,
                target,
                resolveCurrentPosition(ref, store)
        );
    }

    private static boolean diagnosticsEnabled() {
        return NeedsSeekDiagnostics.isEnabled() || NeedsTelemetryDiagnostics.isEnabled();
    }

    @Nonnull
    private static String resolveNpcId(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        return npc != null && npc.getUuid() != null ? npc.getUuid().toString() : ref.toString();
    }

    @Nullable
    private static UUID resolveNpcUuid(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        return npc == null ? null : npc.getUuid();
    }

    @Nullable
    private static Vector3d resolveCurrentPosition(@Nonnull Ref<EntityStore> ref,
                                                   @Nonnull Store<EntityStore> store) {
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        return transform == null || transform.getPosition() == null
                ? null
                : new Vector3d(transform.getPosition());
    }

    @Nullable
    private static String resolveWorldName(@Nonnull Store<EntityStore> store) {
        if (store.getExternalData() == null || store.getExternalData().getWorld() == null) {
            return null;
        }
        World world = store.getExternalData().getWorld();
        return world == null ? null : world.getName();
    }

    @Nonnull
    private String resolveResourceLabel() {
        return resourceType.label;
    }

    static boolean shouldRunFallbackWaterSearch(double primaryRange, double fallbackRange) {
        return Double.isFinite(fallbackRange) && fallbackRange > 0.0
                && (!Double.isFinite(primaryRange) || fallbackRange > primaryRange + EPSILON);
    }

    static int activeSeekVerticalScanRadius(int configuredVerticalScanRadius, double searchRange) {
        int configured = Math.max(0, configuredVerticalScanRadius);
        if (!Double.isFinite(searchRange) || searchRange <= 0.0) {
            return configured;
        }
        return Math.max(configured, Math.min(MAX_ACTIVE_SEEK_VERTICAL_SCAN_RADIUS, (int) Math.ceil(searchRange)));
    }

    static int maxActiveSeekVerticalScanRadiusForTests() {
        return MAX_ACTIVE_SEEK_VERTICAL_SCAN_RADIUS;
    }

    static long targetCacheTtlMs(boolean hasTarget) {
        return NeedsResourceTargetCacheAdapter.targetCacheTtlMs(hasTarget);
    }

    static double preflightRejectTtlSecondsForTests() {
        return PREFLIGHT_REJECT_TTL_SECONDS;
    }

    private static boolean shouldBypassPathPreflight(boolean fastModeActive, boolean hasTarget) {
        return fastModeActive && hasTarget;
    }

    static boolean shouldBypassPathPreflightForTests(boolean fastModeActive, boolean hasTarget) {
        return shouldBypassPathPreflight(fastModeActive, hasTarget);
    }

    @Nonnull
    static String fastModeReasonForTests(@Nonnull String reason) {
        return reason.endsWith("_fast_consume") ? reason : reason + "_fast_consume";
    }

    static boolean targetCacheBlockMatchesForTests(@Nonnull Vector3d cachedScanPosition,
                                                   @Nonnull Vector3d currentPosition) {
        return block(cachedScanPosition.x) == block(currentPosition.x)
                && block(cachedScanPosition.y) == block(currentPosition.y)
                && block(cachedScanPosition.z) == block(currentPosition.z);
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

    private static void appendItemIds(@Nonnull LinkedHashMap<String, String> merged,
                                      @Nullable String[] values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            merged.putIfAbsent(normalized, value.trim());
        }
    }

    private static boolean hasAnyItemId(@Nullable String[] values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static String[] sanitizeItemIds(@Nullable String[] values) {
        if (!hasAnyItemId(values)) {
            return new String[0];
        }
        LinkedHashMap<String, String> sanitized = new LinkedHashMap<>();
        appendItemIds(sanitized, values);
        return sanitized.values().toArray(new String[0]);
    }

    private static double sanitizeRange(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 12.0;
    }

    private static double resolveRatio(double value, double min, double max) {
        double range = max - min;
        if (!Double.isFinite(range) || range <= 0.0) {
            return 1.0;
        }
        return clamp01((clamp(value, min, max) - min) / range);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return value < min ? min : value > max ? max : value;
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    private static int block(double value) {
        return (int) Math.floor(value);
    }

    public static boolean rejectTarget(@Nullable UUID npcUuid,
                                       @Nullable String resourceType,
                                       @Nullable Vector3d target,
                                       double suppressSeconds) {
        return NeedsResourceTargetCacheAdapter.rejectTarget(npcUuid, resourceType, target, suppressSeconds);
    }

    static boolean rejectTargetForTests(@Nullable UUID npcUuid,
                                        @Nullable String resourceType,
                                        @Nullable Vector3d target,
                                        double suppressSeconds,
                                        long nowMs) {
        if (resourceType == null || resourceType.isBlank() || "auto".equalsIgnoreCase(resourceType.trim())) {
            boolean water = NeedsResourceTargetCacheAdapter.rejectTarget(npcUuid, "water", target, suppressSeconds, nowMs);
            boolean food = NeedsResourceTargetCacheAdapter.rejectTarget(npcUuid, "food_container", target, suppressSeconds, nowMs);
            return water || food;
        }
        return NeedsResourceTargetCacheAdapter.rejectTarget(npcUuid, resourceType, target, suppressSeconds, nowMs);
    }

    static boolean isTargetRejectedForTests(@Nullable UUID npcUuid,
                                            @Nullable String resourceType,
                                            @Nullable Vector3d target,
                                            long nowMs) {
        return NeedsResourceTargetCacheAdapter.isTargetRejected(npcUuid, resourceType, target, nowMs);
    }

    static void clearRejectedTargetsForTests() {
        PositionTargetRejectCache.clearForTests();
    }

    static int rejectedTargetCountForTests() {
        return PositionTargetRejectCache.countForTests();
    }

    static int rejectedTargetMaxEntriesForTests() {
        return PositionTargetRejectCache.MAX_ENTRIES;
    }

    static boolean reserveTargetForTests(@Nullable UUID npcUuid,
                                         @Nullable String worldName,
                                         @Nullable String resourceType,
                                         @Nullable Vector3d target,
                                         long nowMs) {
        return NeedsResourceTargetCacheAdapter.reserveTarget(
                npcUuid, worldName, resourceType, target, nowMs
        );
    }

    static boolean isTargetReservedByOtherForTests(@Nullable UUID npcUuid,
                                                   @Nullable String worldName,
                                                   @Nullable String resourceType,
                                                   @Nullable Vector3d target,
                                                   long nowMs) {
        return NeedsResourceTargetCacheAdapter.isReservedByOther(
                npcUuid, worldName, resourceType, target, nowMs
        );
    }

    static void releaseTargetForTests(@Nullable UUID npcUuid,
                                      @Nullable String worldName,
                                      @Nullable String resourceType,
                                      @Nullable Vector3d target) {
        NeedsResourceTargetCacheAdapter.releaseTarget(npcUuid, worldName, resourceType, target);
    }

    static void clearTargetReservationsForTests() {
        PositionTargetReservationCache.clearForTests();
    }

    static void rememberFastConsumeTargetForTests(@Nullable UUID npcUuid,
                                                  @Nullable String worldName,
                                                  @Nullable String resourceType,
                                                  @Nullable Vector3d target,
                                                  long expiresAtMs) {
        NeedsResourceTargetCacheAdapter.rememberFastConsumeTargetForTests(
                npcUuid, worldName, resourceType, target, expiresAtMs
        );
    }

    static boolean isFastConsumeTargetForTests(@Nullable UUID npcUuid,
                                               @Nullable String worldName,
                                               @Nullable String resourceType,
                                               @Nullable Vector3d target,
                                               long nowMs) {
        return NeedsResourceTargetCacheAdapter.isFastConsumeTargetForTests(
                npcUuid, worldName, resourceType, target, nowMs
        );
    }

    static void clearFastConsumeTargetsForTests() {
        NeedsResourceTargetCacheAdapter.clearFastConsumeTargetsForTests();
    }

    public static void releaseTarget(@Nullable Ref<EntityStore> npcRef,
                                     @Nullable Store<EntityStore> store,
                                     @Nullable String resourceType,
                                     @Nullable Vector3d target) {
        NeedsResourceTargetCacheAdapter.releaseTarget(npcRef, store, resourceType, target);
    }

    public static boolean hasFastConsumeTarget(@Nullable Ref<EntityStore> npcRef,
                                               @Nullable Store<EntityStore> store,
                                               long nowMs) {
        return NeedsResourceTargetCacheAdapter.hasFastConsumeTarget(npcRef, store, nowMs);
    }

    private record SearchEligibility(boolean allowed,
                                     @Nonnull String reason,
                                     double currentRatio,
                                     @Nullable TwNeedsConfig needsConfig) {
        @Nonnull
        private static SearchEligibility allowed(double ratio, @Nullable TwNeedsConfig config) {
            return new SearchEligibility(true, "eligible", ratio, config);
        }

        @Nonnull
        private static SearchEligibility blocked(@Nonnull String reason,
                                                 double ratio,
                                                 @Nullable TwNeedsConfig config) {
            return new SearchEligibility(false, reason, ratio, config);
        }
    }

    private record FoodItemIdsCacheKey(@Nonnull String configId,
                                       @Nonnull List<String> passiveItemIds,
                                       boolean hasConfiguredItemIds) {
        @Nonnull
        private static FoodItemIdsCacheKey from(@Nonnull TwNeedsConfig config,
                                                @Nonnull String[] itemIds,
                                                boolean hasConfiguredItemIds) {
            String id = config.getId();
            return new FoodItemIdsCacheKey(
                    id == null || id.isBlank() ? "<default>" : id.trim().toLowerCase(Locale.ROOT),
                    List.of(itemIds),
                    hasConfiguredItemIds
            );
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
                case "hunger" -> HUNGER;
                case "thirst" -> THIRST;
                default -> null;
            };
        }
    }

    private enum ResourceType {
        WATER("water", "Water"),
        FOOD_CONTAINER("food_container", "FoodContainer");

        private final String kind;
        private final String label;

        ResourceType(String kind, String label) {
            this.kind = kind;
            this.label = label;
        }

        @Nonnull
        private static ResourceType from(@Nullable String raw) {
            if (raw == null) {
                return WATER;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            return normalized.equals("food")
                    || normalized.equals("foodcontainer")
                    || normalized.equals("food_container")
                    ? FOOD_CONTAINER
                    : WATER;
        }
    }
}
