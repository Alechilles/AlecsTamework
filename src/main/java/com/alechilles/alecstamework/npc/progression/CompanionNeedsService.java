package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.util.Locale;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Applies hunger/thirst progression and triggers equilibrium-happiness reconciliation.
 */
public final class CompanionNeedsService {
    private static final double EPSILON = 0.000001;
    private static final double SECONDS_PER_MINUTE = 60.0;
    private static final CompanionNeedsEnvironmentService ENVIRONMENT_SERVICE = new CompanionNeedsEnvironmentService();

    private CompanionNeedsService() {
    }

    /**
     * Ensures a valid needs component exists for the NPC and seeds config defaults when missing.
     */
    @Nullable
    public static TameworkNeedsComponent ensureNeedsComponent(@Nullable Ref<EntityStore> npcRef,
                                                              @Nullable Store<EntityStore> store,
                                                              @Nullable String roleId) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return null;
        }
        TwNeedsConfig config = resolveNeedsConfig(npcRef, store, roleId, store.getComponent(npcRef, needsType));
        if (config == null || !config.isEnabled()) {
            return null;
        }
        TwNeedsConfig.ValueSettings values = config.getValues();
        long nowMs = resolveNowMs(config, store);
        TameworkNeedsComponent existing = store.getComponent(npcRef, needsType);
        if (existing == null) {
            TameworkNeedsComponent created = new TameworkNeedsComponent(
                    config.getId(),
                    values.getHungerDefault(),
                    values.getThirstDefault(),
                    0.0,
                    nowMs,
                    nowMs
            );
            store.putComponent(npcRef, needsType, created);
            return created;
        }
        boolean changed = false;
        if ((existing.getConfigId() == null || existing.getConfigId().isBlank())
                && config.getId() != null
                && !config.getId().isBlank()) {
            existing.setConfigId(config.getId());
            changed = true;
        }
        double hunger = sanitizeAndClamp(existing.getHunger(), values.getHungerDefault(), values.getHungerMin(), values.getHungerMax());
        double thirst = sanitizeAndClamp(existing.getThirst(), values.getThirstDefault(), values.getThirstMin(), values.getThirstMax());
        if (Math.abs(existing.getHunger() - hunger) > EPSILON || !Double.isFinite(existing.getHunger())) {
            existing.setHunger(hunger);
            changed = true;
        }
        if (Math.abs(existing.getThirst() - thirst) > EPSILON || !Double.isFinite(existing.getThirst())) {
            existing.setThirst(thirst);
            changed = true;
        }
        double appliedPenalty = Double.isFinite(existing.getAppliedHappinessPenalty())
                ? existing.getAppliedHappinessPenalty()
                : 0.0;
        if (!Double.isFinite(existing.getAppliedHappinessPenalty())) {
            existing.setAppliedHappinessPenalty(appliedPenalty);
            changed = true;
        }
        if (existing.getLastUpdateMs() <= 0L) {
            existing.setLastUpdateMs(nowMs);
            changed = true;
        }
        if (existing.getLastPassiveSweepMs() <= 0L) {
            existing.setLastPassiveSweepMs(nowMs);
            changed = true;
        }
        if (changed) {
            store.putComponent(npcRef, needsType, existing);
        }
        return existing;
    }

    /**
     * Runs a needs progression step: decay plus equilibrium happiness reconciliation.
     */
    public static boolean tickNeeds(@Nullable Ref<EntityStore> npcRef,
                                    @Nullable Store<EntityStore> store,
                                    @Nullable String roleId) {
        return runNeedsUpdate(npcRef, store, roleId, 0.0, 0.0, false, null);
    }

    /**
     * Applies feed-interaction refill and optional thirst refill from configured water-bucket items.
     */
    public static boolean applyFeedInteractionRefill(@Nullable Ref<EntityStore> npcRef,
                                                     @Nullable Store<EntityStore> store,
                                                     @Nullable String heldItemId) {
        return runNeedsUpdate(npcRef, store, null, 0.0, 0.0, true, heldItemId);
    }

    /**
     * Applies an explicit consume attempt for water and/or food from action-driven seek flow.
     */
    public static boolean applyResourceConsume(@Nullable Ref<EntityStore> npcRef,
                                               @Nullable Store<EntityStore> store,
                                               @Nullable String roleId,
                                               @Nullable String resourceType,
                                               @Nullable String[] preferredFoodItemIds) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return false;
        }
        TameworkNeedsComponent component = store.getComponent(npcRef, needsType);
        TwNeedsConfig config = resolveNeedsConfig(npcRef, store, roleId, component);
        if (config == null || !config.isEnabled()) {
            return false;
        }
        TwNeedsConfig.PassiveRefillSettings passiveRefill = config.getPassiveRefill();
        NeedsResourceConsumeMode mode = NeedsResourceConsumeMode.from(resourceType);
        double hungerGain = 0.0;
        double thirstGain = 0.0;

        if (mode.consumesFood() && passiveRefill.isNearbyContainerFeedEnabled()) {
            String[] effectiveFoodIds = preferredFoodItemIds;
            if (effectiveFoodIds == null || effectiveFoodIds.length == 0) {
                effectiveFoodIds = resolveRoleFoodItemIds(npcRef, store);
            }
            int consumed = ENVIRONMENT_SERVICE.consumeNearbyContainerFood(npcRef, store, config, effectiveFoodIds);
            if (consumed > 0) {
                hungerGain = consumed * passiveRefill.getHungerGainPerConsumedItem();
            }
        }
        if (mode.consumesWater()
                && passiveRefill.isNearbyWaterDrinkEnabled()) {
            if (mode == NeedsResourceConsumeMode.WATER) {
                thirstGain = passiveRefill.getThirstGainPerSweepNearWater();
            } else if (ENVIRONMENT_SERVICE.isNearWater(npcRef, store, config)) {
                thirstGain = passiveRefill.getThirstGainPerSweepNearWater();
            }
        }
        if (hungerGain <= 0.0 && thirstGain <= 0.0) {
            return false;
        }
        return runNeedsUpdate(npcRef, store, roleId, hungerGain, thirstGain, false, null);
    }

    private static boolean runNeedsUpdate(@Nullable Ref<EntityStore> npcRef,
                                          @Nullable Store<EntityStore> store,
                                          @Nullable String roleId,
                                          double explicitHungerGain,
                                          double explicitThirstGain,
                                          boolean includeConfiguredManualGains,
                                          @Nullable String heldItemId) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return false;
        }
        TameworkNeedsComponent component = store.getComponent(npcRef, needsType);
        TwNeedsConfig config = resolveNeedsConfig(npcRef, store, roleId, component);
        if (config == null || !config.isEnabled()) {
            return false;
        }
        component = ensureNeedsComponent(npcRef, store, roleId);
        if (component == null) {
            return false;
        }
        long nowMs = resolveNowMs(config, store);
        TwNeedsConfig.ValueSettings values = config.getValues();
        double hunger = sanitizeAndClamp(component.getHunger(), values.getHungerDefault(), values.getHungerMin(), values.getHungerMax());
        double thirst = sanitizeAndClamp(component.getThirst(), values.getThirstDefault(), values.getThirstMin(), values.getThirstMax());
        long lastUpdateMs = component.getLastUpdateMs();
        long elapsedMs = lastUpdateMs > 0L ? Math.max(0L, nowMs - lastUpdateMs) : 0L;

        boolean componentChanged = false;
        if (elapsedMs > 0L) {
            double elapsedMinutes = elapsedMs / (SECONDS_PER_MINUTE * 1000.0);
            TwNeedsConfig.DecaySettings decay = config.getDecay();
            double hungerDecay = decay.getHungerPerMinute() * elapsedMinutes;
            double thirstDecay = decay.getThirstPerMinute() * elapsedMinutes;
            hunger = clamp(hunger - hungerDecay, values.getHungerMin(), values.getHungerMax());
            thirst = clamp(thirst - thirstDecay, values.getThirstMin(), values.getThirstMax());
            componentChanged = true;
        }

        double hungerGain = explicitHungerGain;
        double thirstGain = explicitThirstGain;
        if (includeConfiguredManualGains) {
            TwNeedsConfig.ManualRefillSettings manualRefill = config.getManualRefill();
            hungerGain += manualRefill.getHungerGainOnFeedInteraction();
            if (ENVIRONMENT_SERVICE.isConfiguredWaterBucketItem(heldItemId, config)) {
                thirstGain += manualRefill.getThirstGainOnWaterBucket();
            }
        }
        if (hungerGain > 0.0) {
            hunger = clamp(hunger + hungerGain, values.getHungerMin(), values.getHungerMax());
            componentChanged = true;
        }
        if (thirstGain > 0.0) {
            thirst = clamp(thirst + thirstGain, values.getThirstMin(), values.getThirstMax());
            componentChanged = true;
        }

        if (!Double.isFinite(component.getAppliedHappinessPenalty())
                || Math.abs(component.getAppliedHappinessPenalty()) > EPSILON) {
            component.setAppliedHappinessPenalty(0.0);
            componentChanged = true;
        }

        if (Math.abs(component.getHunger() - hunger) > EPSILON || !Double.isFinite(component.getHunger())) {
            component.setHunger(hunger);
            componentChanged = true;
        }
        if (Math.abs(component.getThirst() - thirst) > EPSILON || !Double.isFinite(component.getThirst())) {
            component.setThirst(thirst);
            componentChanged = true;
        }
        if (component.getLastUpdateMs() != nowMs) {
            component.setLastUpdateMs(nowMs);
            componentChanged = true;
        }
        if (componentChanged) {
            store.putComponent(npcRef, needsType, component);
        }
        boolean happinessChanged = CompanionHappinessService.reconcile(npcRef, store);
        return componentChanged || happinessChanged;
    }

    @Nullable
    private static TwNeedsConfig resolveNeedsConfig(@Nullable Ref<EntityStore> npcRef,
                                                    @Nullable Store<EntityStore> store,
                                                    @Nullable String roleId,
                                                    @Nullable TameworkNeedsComponent component) {
        if (component != null && component.getConfigId() != null && !component.getConfigId().isBlank()) {
            TwNeedsConfig byId = TwNeedsConfig.resolveById(component.getConfigId());
            if (byId != null) {
                return byId;
            }
        }
        if (roleId != null && !roleId.isBlank()) {
            TwNeedsConfig byRoleId = TwNeedsConfig.resolveForRole(roleId);
            if (byRoleId != null) {
                return byRoleId;
            }
        }
        return NeedsConfigResolver.resolveConfig(npcRef, store, component);
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
            return sanitizeItemIds(new String[] { value });
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

    private static long resolveNowMs(@Nonnull TwNeedsConfig config, @Nullable Store<EntityStore> store) {
        return switch (config.getTiming().getTimerBasis()) {
            case WORLD_TIME_SCALED -> BreedingTimeService.resolveCurrentTimeMs(store);
            case REAL_TIME -> System.currentTimeMillis();
        };
    }

    private static double sanitizeAndClamp(double value, double fallback, double min, double max) {
        double safe = Double.isFinite(value) ? value : fallback;
        return clamp(safe, min, max);
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
            if (normalized.equals("food") || normalized.equals("foodcontainer") || normalized.equals("food_container")) {
                return FOOD_CONTAINER;
            }
            if (normalized.equals("water")) {
                return WATER;
            }
            return AUTO;
        }
    }
}
