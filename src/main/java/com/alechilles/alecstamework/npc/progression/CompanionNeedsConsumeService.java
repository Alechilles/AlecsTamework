package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.compat.NpcSupportAccess;
import com.alechilles.alecstamework.npc.params.StdScopeLookupCache;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Handles action-driven needs consumption (food container and/or nearby water).
 */
public final class CompanionNeedsConsumeService {
    private static final double ACTION_FOOD_CONSUME_RADIUS_FLOOR = 2.0;
    private static final CompanionNeedsEnvironmentService ENVIRONMENT_SERVICE = new CompanionNeedsEnvironmentService();
    private static final StdScopeLookupCache SCOPE_LOOKUP_CACHE = new StdScopeLookupCache();

    private CompanionNeedsConsumeService() {
    }

    /**
     * Applies an explicit consume attempt for water and/or food from action-driven seek flow.
     */
    public static boolean applyResourceConsume(@Nullable Ref<EntityStore> npcRef,
                                               @Nullable Store<EntityStore> store,
                                               @Nullable String roleId,
                                               @Nullable String resourceType,
                                               @Nullable String[] preferredFoodItemIds) {
        return applyResourceConsume(
                npcRef,
                store,
                roleId,
                resourceType,
                preferredFoodItemIds,
                null
        );
    }

    /**
     * Applies an explicit consume attempt for water and/or food from action-driven seek flow.
     */
    public static boolean applyResourceConsume(@Nullable Ref<EntityStore> npcRef,
                                               @Nullable Store<EntityStore> store,
                                               @Nullable String roleId,
                                               @Nullable String resourceType,
                                               @Nullable String[] preferredFoodItemIds,
                                               @Nullable Vector3d consumeOriginOverride) {
        return applyResourceConsumeInternal(
                npcRef,
                store,
                roleId,
                resourceType,
                preferredFoodItemIds,
                consumeOriginOverride,
                false
        );
    }

    /**
     * Applies an explicit consume attempt and emits structured diagnostics for failed attempts.
     */
    public static boolean applyResourceConsumeWithDiagnostics(@Nullable Ref<EntityStore> npcRef,
                                                              @Nullable Store<EntityStore> store,
                                                              @Nullable String roleId,
                                                              @Nullable String resourceType,
                                                              @Nullable String[] preferredFoodItemIds) {
        return applyResourceConsumeWithDiagnostics(
                npcRef,
                store,
                roleId,
                resourceType,
                preferredFoodItemIds,
                null
        );
    }

    /**
     * Applies an explicit consume attempt and emits structured diagnostics for failed attempts.
     */
    public static boolean applyResourceConsumeWithDiagnostics(@Nullable Ref<EntityStore> npcRef,
                                                              @Nullable Store<EntityStore> store,
                                                              @Nullable String roleId,
                                                              @Nullable String resourceType,
                                                              @Nullable String[] preferredFoodItemIds,
                                                              @Nullable Vector3d consumeOriginOverride) {
        return applyResourceConsumeInternal(
                npcRef,
                store,
                roleId,
                resourceType,
                preferredFoodItemIds,
                consumeOriginOverride,
                true
        );
    }

    public static void logResourceConsumeActionReached(@Nullable Ref<EntityStore> npcRef,
                                                       @Nullable Store<EntityStore> store,
                                                       @Nullable String roleId,
                                                       @Nullable String resourceType,
                                                       @Nullable Vector3d consumeOriginOverride) {
        NeedsConsumeDiagnostics.maybeLogRuntime(
                true,
                NeedsConsumeDiagnostics.resolveNpcId(npcRef, store),
                roleId,
                NeedsResourceConsumeMode.from(resourceType).name(),
                "action_reached",
                "pending",
                0,
                0.0,
                0.0,
                consumeOriginOverride
        );
    }

    private static boolean applyResourceConsumeInternal(@Nullable Ref<EntityStore> npcRef,
                                                        @Nullable Store<EntityStore> store,
                                                        @Nullable String roleId,
                                                        @Nullable String resourceType,
                                                        @Nullable String[] preferredFoodItemIds,
                                                        @Nullable Vector3d consumeOriginOverride,
                                                        boolean diagnostics) {
        String npcId = NeedsConsumeDiagnostics.resolveNpcId(npcRef, store);
        NeedsResourceConsumeMode mode = NeedsResourceConsumeMode.from(resourceType);
        StringBuilder failureReasons = new StringBuilder();
        if (npcRef == null || store == null || !npcRef.isValid()) {
            NeedsConsumeDiagnostics.appendFailureReason(failureReasons, "invalid_context");
            NeedsConsumeDiagnostics.maybeLogConsume(
                    diagnostics,
                    NeedsConsumeDiagnostics.LogLevel.INFO,
                    npcId,
                    roleId,
                    mode.name(),
                    failureReasons.toString(),
                    0,
                    0.0,
                    0.0
            );
            return false;
        }

        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            NeedsConsumeDiagnostics.appendFailureReason(failureReasons, "needs_component_type_missing");
            NeedsConsumeDiagnostics.maybeLogConsume(
                    diagnostics,
                    NeedsConsumeDiagnostics.LogLevel.INFO,
                    npcId,
                    roleId,
                    mode.name(),
                    failureReasons.toString(),
                    0,
                    0.0,
                    0.0
            );
            return false;
        }
        TameworkNeedsComponent component = store.getComponent(npcRef, needsType);
        TwNeedsConfig config = CompanionNeedsService.resolveNeedsConfig(npcRef, store, roleId, component);
        if (!NeedsConfigResolver.isRuntimeEnabled(config)) {
            NeedsConsumeDiagnostics.appendFailureReason(failureReasons, "config_missing_or_disabled");
            NeedsConsumeDiagnostics.maybeLogConsume(
                    diagnostics,
                    NeedsConsumeDiagnostics.LogLevel.INFO,
                    npcId,
                    roleId,
                    mode.name(),
                    failureReasons.toString(),
                    0,
                    0.0,
                    0.0
            );
            return false;
        }

        TwNeedsConfig.PassiveRefillSettings passiveRefill = config.getPassiveRefill();
        double hungerGain = 0.0;
        double thirstGain = 0.0;
        int consumedItems = 0;
        boolean waterRefillApplied = false;
        Map<String, Integer> consumedItemCountsByItemId = null;

        if (mode.consumesFood()) {
            if (!passiveRefill.isNearbyContainerFeedEnabled()) {
                NeedsConsumeDiagnostics.appendFailureReason(failureReasons, "food_refill_disabled");
            } else {
                String[] effectiveFoodIds = preferredFoodItemIds;
                if (effectiveFoodIds == null || effectiveFoodIds.length == 0) {
                    effectiveFoodIds = resolveRoleFoodItemIds(npcRef, store);
                }
                // Seek flow targets an adjacent stand position, so allow a small floor to
                // account for stop-position variance around the intended container.
                double consumeRadius = Math.max(
                        passiveRefill.getContainerConsumeRadius(),
                        ACTION_FOOD_CONSUME_RADIUS_FLOOR
                );
                CompanionNeedsEnvironmentService.ContainerConsumeResult containerResult = null;
                if (isFiniteConsumeOrigin(consumeOriginOverride)) {
                    containerResult = ENVIRONMENT_SERVICE.consumeContainerFoodAtDetailed(
                            npcRef,
                            store,
                            config,
                            roleId,
                            effectiveFoodIds,
                            consumeOriginOverride
                    );
                }
                if (containerResult == null || containerResult.getConsumedItems() <= 0) {
                    containerResult = ENVIRONMENT_SERVICE.consumeNearbyContainerFoodDetailed(
                            npcRef,
                            store,
                            config,
                            roleId,
                            effectiveFoodIds,
                            consumeRadius,
                            consumeOriginOverride
                    );
                }
                consumedItems = containerResult.getConsumedItems();
                if (consumedItems > 0) {
                    hungerGain = consumedItems * passiveRefill.getHungerGainPerConsumedItem();
                    consumedItemCountsByItemId = containerResult.getConsumedItemCountsByItemId();
                } else {
                    NeedsConsumeDiagnostics.appendFailureReason(
                            failureReasons,
                            "no_container_food_consumed(" + containerResult.toSummary() + ")"
                    );
                }
            }
        }

        if (mode.consumesWater()) {
            if (!passiveRefill.isNearbyWaterDrinkEnabled()) {
                NeedsConsumeDiagnostics.appendFailureReason(failureReasons, "water_refill_disabled");
            } else {
                boolean targetFirstProbe = isFiniteConsumeOrigin(consumeOriginOverride);
                boolean consumedTroughCharge = targetFirstProbe
                        && ENVIRONMENT_SERVICE.consumeWaterTroughChargeAt(store, consumeOriginOverride);
                if (!consumedTroughCharge) {
                    consumedTroughCharge = ENVIRONMENT_SERVICE.consumeNearbyWaterTroughCharge(
                            npcRef,
                            store,
                            config,
                            consumeOriginOverride
                    );
                }
                boolean nearWater = consumedTroughCharge
                        || (targetFirstProbe && ENVIRONMENT_SERVICE.isConsumableWaterAt(store, consumeOriginOverride));
                if (!nearWater) {
                    nearWater = ENVIRONMENT_SERVICE.isNearWater(npcRef, store, config, consumeOriginOverride);
                }
                if (!nearWater && targetFirstProbe) {
                    nearWater = ENVIRONMENT_SERVICE.isNearWater(npcRef, store, config);
                }
                if (nearWater) {
                    thirstGain = passiveRefill.getThirstGainPerSweepNearWater();
                    waterRefillApplied = true;
                } else {
                    NeedsConsumeDiagnostics.appendFailureReason(failureReasons, "not_near_water");
                }
            }
        }

        if (hungerGain <= 0.0 && thirstGain <= 0.0) {
            if (failureReasons.length() == 0) {
                NeedsConsumeDiagnostics.appendFailureReason(failureReasons, "no_refill_applied");
            }
            NeedsConsumeDiagnostics.maybeLogConsume(
                    diagnostics,
                    NeedsConsumeDiagnostics.LogLevel.INFO,
                    npcId,
                    roleId,
                    mode.name(),
                    failureReasons.toString(),
                    consumedItems,
                    hungerGain,
                    thirstGain
            );
            return false;
        }

        boolean updated = CompanionNeedsService.runNeedsUpdate(
                npcRef,
                store,
                roleId,
                hungerGain,
                thirstGain,
                false,
                false,
                null,
                null
        );
        boolean happinessChanged = CompanionHappinessService.reconcileWithFeedEffects(
                npcRef,
                store,
                null,
                false,
                null,
                consumedItemCountsByItemId
        );
        if (shouldAwardFeedXpForResourceConsume(consumedItems, waterRefillApplied, updated, happinessChanged)) {
            CompanionLevelingService.awardFeedXp(npcRef, store);
        }
        if (updated) {
            NeedsConsumeDiagnostics.maybeLogConsume(
                    diagnostics,
                    NeedsConsumeDiagnostics.LogLevel.FINE,
                    npcId,
                    roleId,
                    mode.name(),
                    "success",
                    consumedItems,
                    hungerGain,
                    thirstGain
            );
            NeedsConsumeDiagnostics.maybeLogRuntime(
                    diagnostics,
                    npcId,
                    roleId,
                    mode.name(),
                    "consume_result",
                    "success",
                    consumedItems,
                    hungerGain,
                    thirstGain,
                    consumeOriginOverride
            );
            return true;
        }

        if (happinessChanged) {
            NeedsConsumeDiagnostics.maybeLogConsume(
                    diagnostics,
                    NeedsConsumeDiagnostics.LogLevel.FINE,
                    npcId,
                    roleId,
                    mode.name(),
                    "success_happiness_only",
                    consumedItems,
                    hungerGain,
                    thirstGain
            );
            NeedsConsumeDiagnostics.maybeLogRuntime(
                    diagnostics,
                    npcId,
                    roleId,
                    mode.name(),
                    "consume_result",
                    "success_happiness_only",
                    consumedItems,
                    hungerGain,
                    thirstGain,
                    consumeOriginOverride
            );
            return true;
        }

        NeedsConsumeDiagnostics.appendFailureReason(failureReasons, "needs_update_no_change");
        NeedsConsumeDiagnostics.maybeLogConsume(
                diagnostics,
                NeedsConsumeDiagnostics.LogLevel.INFO,
                npcId,
                roleId,
                mode.name(),
                failureReasons.toString(),
                consumedItems,
                hungerGain,
                thirstGain
        );
        return false;
    }

    static boolean shouldAwardFeedXpForResourceConsume(int consumedItems,
                                                       boolean waterRefillApplied,
                                                       boolean updated,
                                                       boolean happinessChanged) {
        return (updated || happinessChanged) && (consumedItems > 0 || waterRefillApplied);
    }

    static boolean canUseTargetFirstConsumeProbeForTests(@Nullable Vector3d consumeOrigin) {
        return isFiniteConsumeOrigin(consumeOrigin);
    }

    private static boolean isFiniteConsumeOrigin(@Nullable Vector3d consumeOrigin) {
        return consumeOrigin != null
                && Double.isFinite(consumeOrigin.x)
                && Double.isFinite(consumeOrigin.y)
                && Double.isFinite(consumeOrigin.z);
    }

    @Nonnull
    private static String[] resolveRoleFoodItemIds(@Nullable Ref<EntityStore> npcRef,
                                                   @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return new String[0];
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null) {
            return new String[0];
        }
        EntitySupport entitySupport = NpcSupportAccess.entity(npc.getRole(), npcRef, store);
        if (entitySupport == null) {
            return new String[0];
        }
        StdScope sensorScope = entitySupport.getSensorScope();
        if (sensorScope == null) {
            return new String[0];
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String itemId : resolveScopeItemIds(sensorScope, "FoodItemIDs")) {
            if (itemId != null && !itemId.isBlank()) {
                merged.add(itemId.trim());
            }
        }
        for (String itemId : resolveScopeItemIds(sensorScope, "AttractiveItemSet")) {
            if (itemId != null && !itemId.isBlank()) {
                merged.add(itemId.trim());
            }
        }
        if (merged.isEmpty()) {
            return new String[0];
        }
        return merged.toArray(new String[0]);
    }

    @Nonnull
    private static String[] resolveScopeItemIds(@Nonnull StdScope sensorScope,
                                                @Nonnull String paramName) {
        String[] values = SCOPE_LOOKUP_CACHE.getStringArrayOrString(sensorScope, paramName);
        return values != null ? sanitizeItemIds(values) : new String[0];
    }

    @Nonnull
    private static String[] sanitizeItemIds(@Nullable String[] values) {
        if (values == null || values.length == 0) {
            return new String[0];
        }
        int count = 0;
        String[] sanitized = new String[values.length];
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            sanitized[count++] = value.trim();
        }
        if (count == 0) {
            return new String[0];
        }
        if (count == sanitized.length) {
            return sanitized;
        }
        String[] resized = new String[count];
        System.arraycopy(sanitized, 0, resized, 0, count);
        return resized;
    }

    /**
     * Consume routing mode for action-triggered resource refill.
     */
    private enum NeedsResourceConsumeMode {
        AUTO,
        FOOD_CONTAINER,
        WATER;

        boolean consumesFood() {
            return this == AUTO || this == FOOD_CONTAINER;
        }

        boolean consumesWater() {
            return this == AUTO || this == WATER;
        }

        @Nonnull
        static NeedsResourceConsumeMode from(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return AUTO;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("food")
                    || normalized.equals("foodcontainer")
                    || normalized.equals("food_container")) {
                return FOOD_CONTAINER;
            }
            if (normalized.equals("water")) {
                return WATER;
            }
            return AUTO;
        }
    }
}
