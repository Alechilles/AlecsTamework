package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.Locale;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Handles action-driven needs consumption (food container and/or nearby water).
 */
public final class CompanionNeedsConsumeService {
    private static final double ACTION_FOOD_CONSUME_RADIUS_FLOOR = 2.0;
    private static final CompanionNeedsEnvironmentService ENVIRONMENT_SERVICE = new CompanionNeedsEnvironmentService();

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
        if (config == null || !config.isEnabled()) {
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

        if (mode.consumesFood()) {
            if (!passiveRefill.isNearbyContainerFeedEnabled()) {
                NeedsConsumeDiagnostics.appendFailureReason(failureReasons, "food_refill_disabled");
            } else {
                String[] effectiveFoodIds = preferredFoodItemIds;
                if (effectiveFoodIds == null || effectiveFoodIds.length == 0) {
                    effectiveFoodIds = resolveRoleFoodItemIds(npcRef, store);
                }
                if (effectiveFoodIds == null || effectiveFoodIds.length == 0) {
                    NeedsConsumeDiagnostics.appendFailureReason(failureReasons, "food_item_ids_empty");
                } else {
                    // Seek flow targets an adjacent stand position, so allow a small floor to
                    // account for stop-position variance around the intended container.
                    double consumeRadius = Math.max(
                            passiveRefill.getContainerConsumeRadius(),
                            ACTION_FOOD_CONSUME_RADIUS_FLOOR
                    );
                    CompanionNeedsEnvironmentService.ContainerConsumeResult containerResult =
                            ENVIRONMENT_SERVICE.consumeNearbyContainerFoodDetailed(
                            npcRef,
                            store,
                            config,
                            effectiveFoodIds,
                            consumeRadius,
                            consumeOriginOverride
                    );
                    consumedItems = containerResult.getConsumedItems();
                    if (consumedItems > 0) {
                        hungerGain = consumedItems * passiveRefill.getHungerGainPerConsumedItem();
                    } else {
                        NeedsConsumeDiagnostics.appendFailureReason(
                                failureReasons,
                                "no_container_food_consumed(" + containerResult.toSummary() + ")"
                        );
                    }
                }
            }
        }

        if (mode.consumesWater()) {
            if (!passiveRefill.isNearbyWaterDrinkEnabled()) {
                NeedsConsumeDiagnostics.appendFailureReason(failureReasons, "water_refill_disabled");
            } else {
                boolean consumedTroughCharge = ENVIRONMENT_SERVICE.consumeNearbyWaterTroughCharge(
                        npcRef,
                        store,
                        config,
                        consumeOriginOverride
                );
                if (!consumedTroughCharge && consumeOriginOverride != null) {
                    consumedTroughCharge = ENVIRONMENT_SERVICE.consumeNearbyWaterTroughCharge(
                            npcRef,
                            store,
                            config
                    );
                }
                boolean nearWater = consumedTroughCharge
                        || ENVIRONMENT_SERVICE.isNearWater(npcRef, store, config, consumeOriginOverride);
                if (!nearWater && consumeOriginOverride != null) {
                    nearWater = ENVIRONMENT_SERVICE.isNearWater(npcRef, store, config);
                }
                if (nearWater) {
                    thirstGain = passiveRefill.getThirstGainPerSweepNearWater();
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
                null
        );
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

    @Nonnull
    private static String[] resolveRoleFoodItemIds(@Nullable Ref<EntityStore> npcRef,
                                                   @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return new String[0];
        }
        NPCEntity npc = store.getComponent(npcRef, NPCEntity.getComponentType());
        if (npc == null || npc.getRole() == null || npc.getRole().getEntitySupport() == null) {
            return new String[0];
        }
        EntitySupport entitySupport = npc.getRole().getEntitySupport();
        StdScope sensorScope = entitySupport.getSensorScope();
        if (sensorScope == null) {
            return new String[0];
        }
        try {
            Supplier<String[]> arraySupplier = sensorScope.getStringArraySupplier("FoodItemIDs");
            if (arraySupplier != null) {
                String[] values = sanitizeItemIds(arraySupplier.get());
                if (values.length > 0) {
                    return values;
                }
            }
        } catch (IllegalStateException ignored) {
            // Fall through to string-param fallback.
        }
        try {
            Supplier<String> stringSupplier = sensorScope.getStringSupplier("FoodItemIDs");
            if (stringSupplier == null) {
                return new String[0];
            }
            String value = stringSupplier.get();
            if (value == null || value.isBlank()) {
                return new String[0];
            }
            return sanitizeItemIds(new String[] {value});
        } catch (IllegalStateException ignored) {
            return new String[0];
        }
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
