package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.progression.NeedsResourceFastModePolicy;
import com.alechilles.alecstamework.npc.progression.NeedsResourcePathPreflightService;
import com.alechilles.alecstamework.npc.progression.NeedsResourcePathPreflightService.PathPreflightResult;
import com.alechilles.alecstamework.npc.progression.NeedsResourceRequestTemplate;
import com.alechilles.alecstamework.npc.progression.NeedsResourceSearchCoordinator;
import com.alechilles.alecstamework.npc.progression.NeedsSeekDiagnostics;
import com.alechilles.alecstamework.npc.progression.NeedsTelemetryDiagnostics;
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
import java.util.Locale;
import java.util.UUID;
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
            NeedsResourcePathPreflightService.shared();
    private static final int MAX_ACTIVE_SEEK_VERTICAL_SCAN_RADIUS = 16;
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
    private final NeedsResourceRequestTemplate.AreaRequestMemo areaRequestMemo =
            new NeedsResourceRequestTemplate.AreaRequestMemo();
    @Nullable
    private NeedsResourceRequestTemplate requestTemplate;
    @Nullable
    private TwNeedsConfig requestTemplateConfig;
    @Nullable
    private String[] requestTemplatePassiveItemIds;
    private double requestTemplateRadius;
    private int requestTemplateVerticalRadius;
    private double requestTemplateConsumeRadius;
    private double requestTemplateConfiguredSearchRadius;
    private int requestTemplateConfiguredVerticalRadius;
    private double requestTemplateConfiguredConsumeRadius;
    private boolean requestTemplateSignatureInitialized;

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
        long nowMs = System.currentTimeMillis();
        NeedsResourceTargetCacheAdapter.Result local = targetCache.resolveLocal(
                npcUuid,
                worldName,
                resourceType.kind,
                originX,
                originY,
                originZ,
                nowMs
        );
        if (local != null) {
            if (local.status() != NeedsResourceTargetCacheAdapter.Status.TARGET || local.target() == null) {
                log(ref, store, "miss", local.reason(), true, eligibility.currentRatio(), null);
                return false;
            }
            Vector3d localTarget = local.target();
            if (local.preflightRequired() && !local.fastConsume()) {
                PathPreflightResult preflight = PATH_PREFLIGHT_SERVICE.preflight(
                        ref,
                        role,
                        store,
                        npcUuid,
                        resourceType.label,
                        localTarget,
                        local.approachRadius(),
                        nowMs
                );
                logPreflight(ref, store, preflight, local.reason(), local.approachRadius(), localTarget);
                if (!preflight.ready()) {
                    if (preflight.noPath()) {
                        PATH_PREFLIGHT_SERVICE.invalidateTarget(
                                npcUuid,
                                worldName,
                                resourceType.label,
                                localTarget
                        );
                        NeedsResourceSearchCoordinator.Request request = createRequest(
                                eligibility.needsConfig() == null
                                        ? resolveNeedsConfig(ref, store)
                                        : eligibility.needsConfig(),
                                worldName,
                                originX,
                                originY,
                                originZ
                        );
                        if (request != null) {
                            targetCache.invalidateCandidate(store, request, localTarget, nowMs);
                        }
                        targetCache.forgetRecentTarget(npcUuid, worldName, resourceType.kind, localTarget);
                        NeedsResourceTargetCacheAdapter.rejectTarget(
                                npcUuid,
                                resourceType.kind,
                                localTarget,
                                NeedsResourceTargetStateFacade.preflightRejectTtlSeconds()
                        );
                        targetCache.clearTarget(npcUuid, worldName, resourceType.kind, localTarget);
                    } else {
                        targetCache.keepPendingTarget(
                                npcUuid,
                                worldName,
                                resourceType.kind,
                                localTarget,
                                nowMs
                        );
                    }
                    log(ref, store, "miss", preflight.reason(), false, eligibility.currentRatio(), localTarget);
                    return false;
                }
                if (!targetCache.promoteTarget(npcUuid, worldName, resourceType.kind, localTarget)) {
                    log(ref, store, "miss", "path_preflight_target_changed", false,
                            eligibility.currentRatio(), localTarget);
                    return false;
                }
                targetCache.rememberRecentTarget(npcUuid, worldName, resourceType.kind, localTarget, nowMs);
            }
            positionInfo.setTarget(localTarget.x, localTarget.y, localTarget.z);
            log(ref, store, "target_found", local.reason(), true, eligibility.currentRatio(), localTarget);
            return true;
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
                    worldName,
                    resourceType.kind,
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
        if (result.preflightRequired() && !result.fastConsume()) {
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
                    PATH_PREFLIGHT_SERVICE.invalidateTarget(
                            npcUuid,
                            worldName,
                            resourceType.label,
                            target
                    );
                    targetCache.invalidateCandidate(store, request, target, nowMs);
                    targetCache.forgetRecentTarget(npcUuid, worldName, resourceType.kind, target);
                    NeedsResourceTargetCacheAdapter.rejectTarget(
                            npcUuid,
                            resourceType.kind,
                            target,
                            NeedsResourceTargetStateFacade.preflightRejectTtlSeconds()
                    );
                    targetCache.clearTarget(npcUuid, worldName, resourceType.kind, target);
                } else {
                    targetCache.keepPendingTarget(
                            npcUuid,
                            worldName,
                            resourceType.kind,
                            target,
                            nowMs
                    );
                }
                log(ref, store, "miss", preflight.reason(), false, eligibility.currentRatio(), target);
                return false;
            }
            if (!targetCache.promoteTarget(npcUuid, worldName, resourceType.kind, target)) {
                log(ref, store, "miss", "path_preflight_target_changed", false,
                        eligibility.currentRatio(), target);
                return false;
            }
        }
        targetCache.rememberRecentTarget(npcUuid, worldName, resourceType.kind, target, nowMs);
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
        NeedsResourceRequestTemplate template = resolveRequestTemplate(config);
        if (template == null) {
            return null;
        }
        try {
            return areaRequestMemo.resolve(template, worldName, originX, originY, originZ);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    private NeedsResourceRequestTemplate resolveRequestTemplate(@Nullable TwNeedsConfig config) {
        double searchRadius = range;
        int verticalRadius = 8;
        double consumeRadius = 0.0;
        double configuredSearchRadius = Double.NaN;
        int configuredVerticalRadius = -1;
        double configuredConsumeRadius = Double.NaN;
        String[] passiveItemIds = null;
        if (config != null) {
            TwNeedsConfig.PassiveRefillSettings passive = config.getPassiveRefill();
            if (resourceType == ResourceType.WATER) {
                configuredSearchRadius = passive.getWaterSearchRadius();
                configuredVerticalRadius = passive.getWaterVerticalScanRadius();
                configuredConsumeRadius = passive.getWaterConsumeRadius();
                searchRadius = Math.max(searchRadius, configuredSearchRadius);
                verticalRadius = activeSeekVerticalScanRadius(configuredVerticalRadius, searchRadius);
                consumeRadius = configuredConsumeRadius;
            } else {
                configuredSearchRadius = passive.getContainerSearchRadius();
                configuredVerticalRadius = passive.getContainerVerticalScanRadius();
                configuredConsumeRadius = passive.getContainerConsumeRadius();
                searchRadius = Math.max(searchRadius, configuredSearchRadius);
                verticalRadius = activeSeekVerticalScanRadius(configuredVerticalRadius, searchRadius);
                consumeRadius = configuredConsumeRadius;
                passiveItemIds = passive.getContainerFoodItemIds();
            }
        }

        if (resourceType == ResourceType.FOOD_CONTAINER
                && !hasConfiguredItemIds
                && !hasAnyItemId(passiveItemIds)) {
            return null;
        }

        if (requestTemplateSignatureMatches(
                config,
                passiveItemIds,
                searchRadius,
                verticalRadius,
                consumeRadius,
                configuredSearchRadius,
                configuredVerticalRadius,
                configuredConsumeRadius
        )) {
            return requestTemplate;
        }

        String[] effectiveItemIds = resourceType == ResourceType.WATER
                ? new String[0]
                : resolveEffectiveFoodItemIds(passiveItemIds);
        NeedsResourceRequestTemplate next = NeedsResourceRequestTemplate.from(
                resourceType.kind,
                searchRadius,
                verticalRadius,
                consumeRadius,
                effectiveItemIds
        );
        requestTemplate = next;
        requestTemplateConfig = config;
        requestTemplatePassiveItemIds = passiveItemIds == null
                ? null
                : Arrays.copyOf(passiveItemIds, passiveItemIds.length);
        requestTemplateRadius = searchRadius;
        requestTemplateVerticalRadius = verticalRadius;
        requestTemplateConsumeRadius = consumeRadius;
        requestTemplateConfiguredSearchRadius = configuredSearchRadius;
        requestTemplateConfiguredVerticalRadius = configuredVerticalRadius;
        requestTemplateConfiguredConsumeRadius = configuredConsumeRadius;
        requestTemplateSignatureInitialized = true;
        return next;
    }

    private boolean requestTemplateSignatureMatches(@Nullable TwNeedsConfig config,
                                                     @Nullable String[] passiveItemIds,
                                                     double searchRadius,
                                                     int verticalRadius,
                                                     double consumeRadius,
                                                     double configuredSearchRadius,
                                                     int configuredVerticalRadius,
                                                     double configuredConsumeRadius) {
        if (!requestTemplateSignatureInitialized
                || requestTemplate == null
                || requestTemplateConfig != config
                || Double.compare(requestTemplateRadius, searchRadius) != 0
                || requestTemplateVerticalRadius != verticalRadius
                || Double.compare(requestTemplateConsumeRadius, consumeRadius) != 0
                || Double.compare(requestTemplateConfiguredSearchRadius, configuredSearchRadius) != 0
                || requestTemplateConfiguredVerticalRadius != configuredVerticalRadius
                || Double.compare(requestTemplateConfiguredConsumeRadius, configuredConsumeRadius) != 0) {
            return false;
        }
        if (resourceType != ResourceType.FOOD_CONTAINER || config == null) {
            return true;
        }
        return Arrays.equals(requestTemplatePassiveItemIds, passiveItemIds);
    }

    @Nonnull
    private String[] resolveEffectiveFoodItemIds(@Nullable String[] passiveItemIds) {
        if (!hasConfiguredItemIds) {
            return sanitizeItemIds(passiveItemIds);
        }
        if (!hasAnyItemId(passiveItemIds)) {
            return itemIds;
        }
        return mergeItemIds(itemIds, passiveItemIds);
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
        return hasConfiguredItemIds ? mergeItemIds(itemIds, passiveItemIds) : passiveItemIds;
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

    public static boolean rejectTarget(@Nullable UUID npcUuid,
                                       @Nullable String resourceType,
                                       @Nullable Vector3d target,
                                       double suppressSeconds) {
        return NeedsResourceTargetStateFacade.rejectTarget(
                npcUuid, resourceType, target, suppressSeconds
        );
    }

    /**
     * Rejects a target and invalidates only the matching path-preflight authority in the given
     * world. The worldless overload remains available for callers that do not have world context.
     */
    public static boolean rejectTarget(@Nullable UUID npcUuid,
                                       @Nullable String worldName,
                                       @Nullable String resourceType,
                                       @Nullable Vector3d target,
                                       double suppressSeconds) {
        return NeedsResourceTargetStateFacade.rejectTarget(
                npcUuid, worldName, resourceType, target, suppressSeconds
        );
    }

    public static void releaseTarget(@Nullable Ref<EntityStore> npcRef,
                                     @Nullable Store<EntityStore> store,
                                     @Nullable String resourceType,
                                     @Nullable Vector3d target) {
        NeedsResourceTargetStateFacade.releaseTarget(npcRef, store, resourceType, target);
    }

    public static boolean hasFastConsumeTarget(@Nullable Ref<EntityStore> npcRef,
                                               @Nullable Store<EntityStore> store,
                                               long nowMs) {
        return NeedsResourceTargetStateFacade.hasFastConsumeTarget(npcRef, store, nowMs);
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
