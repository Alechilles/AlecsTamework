package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwHappinessConfig;
import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
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
        double gain = rules.feedGain;
        double gainMultiplier = TraitModifierService.resolveMultiplier(
                npcRef,
                store,
                "HappinessGainMultiplier",
                1.0
        );
        double adjustedGain = gain * gainMultiplier;
        if (!Double.isFinite(adjustedGain)) {
            adjustedGain = 0.0;
        }
        return applyDelta(npcRef, store, adjustedGain);
    }

    public static boolean applyDelta(@Nullable Ref<EntityStore> npcRef,
                                     @Nullable Store<EntityStore> store,
                                     double delta) {
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
        if (happiness == null && breeding == null && happinessConfig == null) {
            return false;
        }
        TwBreedingConfig breedingConfig = BreedingConfigResolver.resolveConfig(npcRef, store, breeding);
        HappinessRules rules = resolveRules(happinessConfig);
        if (!Double.isFinite(delta)) {
            delta = 0.0;
        }
        long now = System.currentTimeMillis();
        if (happiness == null) {
            double seedValue = breeding != null && Double.isFinite(breeding.getHappiness())
                    ? breeding.getHappiness()
                    : rules.defaultValue;
            String configId = happinessConfig != null ? happinessConfig.getId() : null;
            happiness = new TameworkHappinessComponent(
                    configId,
                    clamp(seedValue, rules.min, rules.max),
                    now
            );
            store.putComponent(npcRef, happinessType, happiness);
        }
        boolean happinessChanged = false;
        if ((happiness.getConfigId() == null || happiness.getConfigId().isBlank())
                && happinessConfig != null
                && happinessConfig.getId() != null
                && !happinessConfig.getId().isBlank()) {
            happiness.setConfigId(happinessConfig.getId());
            happinessChanged = true;
        }
        double previous = Double.isFinite(happiness.getValue()) ? happiness.getValue() : rules.defaultValue;
        double next = clamp(previous + delta, rules.min, rules.max);
        if (Math.abs(next - previous) > EPSILON) {
            happiness.setValue(next);
            happinessChanged = true;
        }
        if (happiness.getLastUpdateMs() != now) {
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
            if (breeding.getLastHappinessUpdateMs() != now) {
                breeding.setLastHappinessUpdateMs(now);
                breedingChanged = true;
            }
            if (breedingConfig != null) {
                boolean ready = next >= breedingConfig.getHappiness().getThreshold();
                if (breeding.isReady() != ready) {
                    breeding.setReady(ready);
                    breedingChanged = true;
                }
                if ((breeding.getConfigId() == null || breeding.getConfigId().isBlank())
                        && breedingConfig.getId() != null
                        && !breedingConfig.getId().isBlank()) {
                    breeding.setConfigId(breedingConfig.getId());
                    breedingChanged = true;
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
            double feedGain = happinessConfig.getSources().getGainOnFeed();
            return new HappinessRules(min, max, defaultValue, feedGain);
        }
        return new HappinessRules(DEFAULT_MIN, DEFAULT_MAX, DEFAULT_VALUE, DEFAULT_FEED_GAIN);
    }

    private record HappinessRules(double min, double max, double defaultValue, double feedGain) {
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
