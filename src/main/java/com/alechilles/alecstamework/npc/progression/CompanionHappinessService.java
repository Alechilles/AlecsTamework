package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Applies companion happiness progression updates from gameplay events.
 *
 * <p>Primary storage is {@link TameworkHappinessComponent}. Breeding state is mirrored for backward
 * compatibility while migration is in progress.
 */
public final class CompanionHappinessService {
    private static final double EPSILON = 0.000001;
    private static final double DEFAULT_MIN = 0.0;
    private static final double DEFAULT_MAX = 100.0;
    private static final double DEFAULT_VALUE = 50.0;
    private static final double DEFAULT_FEED_GAIN = 5.0;
    private static final double SECONDS_PER_MINUTE = 60.0;

    private CompanionHappinessService() {
    }

    public static boolean applyFeedGain(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        TameworkHappinessComponent happiness = happinessType != null ? store.getComponent(npcRef, happinessType) : null;
        TwHappinessConfig happinessConfig = HappinessConfigResolver.resolveConfig(npcRef, store, happiness);
        HappinessRules rules = resolveRules(happinessConfig);
        return applyImpulse(npcRef, store, rules.feedGain);
    }

    public static boolean reconcile(@Nullable Ref<EntityStore> npcRef,
                                    @Nullable Store<EntityStore> store) {
        return updateHappiness(npcRef, store, 0.0);
    }

    public static boolean applyImpulse(@Nullable Ref<EntityStore> npcRef,
                                       @Nullable Store<EntityStore> store,
                                       double impulseDelta) {
        return updateHappiness(npcRef, store, impulseDelta);
    }

    public static boolean applyDelta(@Nullable Ref<EntityStore> npcRef,
                                     @Nullable Store<EntityStore> store,
                                     double delta) {
        return applyImpulse(npcRef, store, delta);
    }

    @Nullable
    public static HappinessSnapshot resolveSnapshot(@Nullable Ref<EntityStore> npcRef,
                                                    @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return null;
        }
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        TameworkHappinessComponent happiness = happinessType != null ? store.getComponent(npcRef, happinessType) : null;
        TameworkBreedingComponent breeding = breedingType != null ? store.getComponent(npcRef, breedingType) : null;
        TwHappinessConfig happinessConfig = HappinessConfigResolver.resolveConfig(npcRef, store, happiness);
        if (happiness == null && breeding == null && (happinessConfig == null || !happinessConfig.isEnabled())) {
            return null;
        }
        HappinessRules rules = resolveRules(happinessConfig);
        double current = resolveCurrent(happiness, breeding, rules.defaultValue);
        CompanionHappinessModifierService.ModifierSnapshot modifierSnapshot =
                CompanionHappinessModifierService.resolve(npcRef, store, happinessConfig);
        double target = clamp(modifierSnapshot.target(), rules.min, rules.max);
        double baseSetpoint = clamp(modifierSnapshot.baseSetpoint(), rules.min, rules.max);
        return new HappinessSnapshot(
                clamp(current, rules.min, rules.max),
                rules.min,
                rules.max,
                baseSetpoint,
                target,
                modifierSnapshot.modifiers()
        );
    }

    private static boolean updateHappiness(@Nullable Ref<EntityStore> npcRef,
                                           @Nullable Store<EntityStore> store,
                                           double impulseDelta) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        if (happinessType == null) {
            return false;
        }
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        TameworkHappinessComponent happiness = store.getComponent(npcRef, happinessType);
        TameworkBreedingComponent breeding = breedingType != null ? store.getComponent(npcRef, breedingType) : null;
        TwHappinessConfig happinessConfig = HappinessConfigResolver.resolveConfig(npcRef, store, happiness);
        if (happiness == null && breeding == null && (happinessConfig == null || !happinessConfig.isEnabled())) {
            return false;
        }
        TwBreedingConfig breedingConfig = BreedingConfigResolver.resolveConfig(npcRef, store, breeding);
        HappinessRules rules = resolveRules(happinessConfig);
        if (!Double.isFinite(impulseDelta)) {
            impulseDelta = 0.0;
        }
        impulseDelta = applyDispositionToImpulse(npcRef, store, impulseDelta);
        long now = System.currentTimeMillis();
        boolean happinessChanged = false;
        if (happiness == null) {
            double seedValue = resolveCurrent(happiness, breeding, rules.defaultValue);
            String configId = happinessConfig != null ? happinessConfig.getId() : null;
            happiness = new TameworkHappinessComponent(
                    configId,
                    clamp(seedValue, rules.min, rules.max),
                    now
            );
            happinessChanged = true;
        }
        if (happinessConfig != null
                && happinessConfig.getId() != null
                && !happinessConfig.getId().isBlank()) {
            String resolvedConfigId = happinessConfig.getId();
            if (happiness.getConfigId() == null
                    || happiness.getConfigId().isBlank()
                    || !resolvedConfigId.equalsIgnoreCase(happiness.getConfigId())) {
                happiness.setConfigId(resolvedConfigId);
                happinessChanged = true;
            }
        }
        double previous = resolveCurrent(happiness, breeding, rules.defaultValue);
        CompanionHappinessModifierService.ModifierSnapshot modifierSnapshot =
                CompanionHappinessModifierService.resolve(npcRef, store, happinessConfig);
        double target = clamp(modifierSnapshot.target(), rules.min, rules.max);
        long lastUpdateMs = happiness.getLastUpdateMs();
        long elapsedMs = lastUpdateMs > 0L ? Math.max(0L, now - lastUpdateMs) : 0L;
        double elapsedMinutes = elapsedMs / (SECONDS_PER_MINUTE * 1000.0);
        double convergenceStep = rules.convergencePerMinute * elapsedMinutes;
        double converged = moveToward(previous, target, convergenceStep);
        double next = clamp(converged + impulseDelta, rules.min, rules.max);
        if (Math.abs(next - previous) > EPSILON) {
            happiness.setValue(next);
            happinessChanged = true;
        }
        if ((happinessChanged || Math.abs(impulseDelta) > EPSILON || elapsedMs > 0L)
                && happiness.getLastUpdateMs() != now) {
            happiness.setLastUpdateMs(now);
            happinessChanged = true;
        }
        if (happinessChanged) {
            store.putComponent(npcRef, happinessType, happiness);
        }

        boolean breedingChanged = false;
        if (breeding != null && breedingType != null) {
            if (!Double.isFinite(breeding.getHappiness()) || Math.abs(breeding.getHappiness() - next) > EPSILON) {
                breeding.setHappiness(next);
                breedingChanged = true;
            }
            if ((breedingChanged || Math.abs(impulseDelta) > EPSILON || elapsedMs > 0L)
                    && breeding.getLastHappinessUpdateMs() != now) {
                breeding.setLastHappinessUpdateMs(now);
                breedingChanged = true;
            }
            if (breedingConfig != null) {
                String roleId = CompanionRoleIdResolver.resolveRoleId(npcRef, store);
                boolean ready = breeding.isEnabled() && next >= breedingConfig.resolveHappiness(roleId).getThreshold();
                if (breeding.isReady() != ready) {
                    breeding.setReady(ready);
                    breedingChanged = true;
                }
                if (breedingConfig.getId() != null && !breedingConfig.getId().isBlank()) {
                    String resolvedBreedingConfigId = breedingConfig.getId();
                    if (breeding.getConfigId() == null
                            || breeding.getConfigId().isBlank()
                            || !resolvedBreedingConfigId.equalsIgnoreCase(breeding.getConfigId())) {
                        breeding.setConfigId(resolvedBreedingConfigId);
                        breedingChanged = true;
                    }
                }
            }
            if (breedingChanged) {
                store.putComponent(npcRef, breedingType, breeding);
            }
        }
        return happinessChanged || breedingChanged;
    }

    public static double resolveCurrentValue(@Nullable Ref<EntityStore> npcRef,
                                             @Nullable Store<EntityStore> store,
                                             double fallback) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return fallback;
        }
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        if (happinessType != null) {
            TameworkHappinessComponent happiness = store.getComponent(npcRef, happinessType);
            if (happiness != null && Double.isFinite(happiness.getValue())) {
                return happiness.getValue();
            }
        }
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        if (breedingType != null) {
            TameworkBreedingComponent breeding = store.getComponent(npcRef, breedingType);
            if (breeding != null && Double.isFinite(breeding.getHappiness())) {
                return breeding.getHappiness();
            }
        }
        return fallback;
    }

    private static HappinessRules resolveRules(@Nullable TwHappinessConfig happinessConfig) {
        if (happinessConfig != null && happinessConfig.isEnabled()) {
            TwHappinessConfig.ValueSettings values = happinessConfig.getValues();
            double min = values.getMin();
            double max = values.getMax();
            if (max < min) {
                double swap = min;
                min = max;
                max = swap;
            }
            double defaultValue = clamp(values.getCurrentDefault(), min, max);
            double convergencePerMinute = happinessConfig.getEquilibrium().getConvergencePerMinute();
            double feedGain = happinessConfig.getImpulses().getGainOnFeed();
            return new HappinessRules(min, max, defaultValue, convergencePerMinute, feedGain);
        }
        return new HappinessRules(DEFAULT_MIN, DEFAULT_MAX, DEFAULT_VALUE, 0.0, DEFAULT_FEED_GAIN);
    }

    private static double resolveCurrent(@Nullable TameworkHappinessComponent happiness,
                                         @Nullable TameworkBreedingComponent breeding,
                                         double fallback) {
        if (happiness != null && Double.isFinite(happiness.getValue())) {
            return happiness.getValue();
        }
        if (breeding != null && Double.isFinite(breeding.getHappiness())) {
            return breeding.getHappiness();
        }
        return fallback;
    }

    private static double applyDispositionToImpulse(@Nullable Ref<EntityStore> npcRef,
                                                    @Nullable Store<EntityStore> store,
                                                    double impulseDelta) {
        if (!Double.isFinite(impulseDelta) || Math.abs(impulseDelta) <= EPSILON) {
            return 0.0;
        }
        double dispositionMultiplier = TraitModifierService.resolveMultiplier(
                npcRef,
                store,
                "HappinessGainMultiplier",
                1.0
        );
        return CompanionHappinessModifierService.applyDispositionToOffset(impulseDelta, dispositionMultiplier);
    }

    private static double moveToward(double current, double target, double maxStep) {
        if (!Double.isFinite(current)) {
            return target;
        }
        if (!Double.isFinite(target)) {
            return current;
        }
        if (!Double.isFinite(maxStep) || maxStep <= 0.0) {
            return current;
        }
        if (current < target) {
            return Math.min(target, current + maxStep);
        }
        if (current > target) {
            return Math.max(target, current - maxStep);
        }
        return current;
    }

    private record HappinessRules(double min,
                                  double max,
                                  double defaultValue,
                                  double convergencePerMinute,
                                  double feedGain) {
    }

    /**
     * Read-only happiness state used by UI and debug command output.
     */
    public record HappinessSnapshot(double value,
                                    double min,
                                    double max,
                                    double baseSetpoint,
                                    double target,
                                    List<CompanionHappinessModifierService.ModifierEntry> modifiers) {
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
