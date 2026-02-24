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

    private CompanionHappinessService() {
    }

    public static boolean applyFeedGain(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, TameworkHappinessComponent> happinessType = TameworkHappinessComponent.getComponentType();
        if (happinessType == null) {
            return false;
        }
        ComponentType<EntityStore, TameworkBreedingComponent> breedingType = TameworkBreedingComponent.getComponentType();
        TameworkBreedingComponent breeding = breedingType != null ? store.getComponent(npcRef, breedingType) : null;
        if (breeding == null && store.getComponent(npcRef, happinessType) == null) {
            return false;
        }
        TwHappinessConfig happinessConfig = HappinessConfigResolver.resolveConfig(
                npcRef,
                store,
                store.getComponent(npcRef, happinessType)
        );
        TwBreedingConfig breedingConfig = BreedingConfigResolver.resolveConfig(npcRef, store, breeding);
        HappinessRules rules = resolveRules(happinessConfig, breedingConfig);
        if (rules == null) {
            return false;
        }
        TameworkHappinessComponent happiness = store.getComponent(npcRef, happinessType);
        if (happiness == null) {
            double seedValue = breeding != null ? breeding.getHappiness() : rules.defaultValue;
            String configId = happinessConfig != null ? happinessConfig.getId() : null;
            happiness = new TameworkHappinessComponent(configId, clamp(seedValue, rules.min, rules.max), System.currentTimeMillis());
            store.putComponent(npcRef, happinessType, happiness);
        }
        boolean changed = false;
        if ((happiness.getConfigId() == null || happiness.getConfigId().isBlank())
                && happinessConfig != null
                && happinessConfig.getId() != null
                && !happinessConfig.getId().isBlank()) {
            happiness.setConfigId(happinessConfig.getId());
            changed = true;
        }
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
        double previous = happiness.getValue();
        double next = clamp(previous + adjustedGain, rules.min, rules.max);
        if (Math.abs(next - previous) > EPSILON) {
            happiness.setValue(next);
            changed = true;
        }
        long now = System.currentTimeMillis();
        if (happiness.getLastUpdateMs() != now) {
            happiness.setLastUpdateMs(now);
            changed = true;
        }
        if (changed) {
            store.putComponent(npcRef, happinessType, happiness);
        }
        if (breeding != null && breedingType != null) {
            boolean breedingChanged = false;
            if (Math.abs(breeding.getHappiness() - next) > EPSILON) {
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
            changed |= breedingChanged;
        }
        return changed;
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

    @Nullable
    private static HappinessRules resolveRules(@Nullable TwHappinessConfig happinessConfig,
                                               @Nullable TwBreedingConfig breedingConfig) {
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
        if (breedingConfig != null && breedingConfig.isEnabled()) {
            TwBreedingConfig.HappinessSettings settings = breedingConfig.getHappiness();
            double min = settings.getMin();
            double max = settings.getMax();
            if (max < min) {
                double swap = min;
                min = max;
                max = swap;
            }
            double defaultValue = clamp(settings.getCurrentDefault(), min, max);
            double feedGain = settings.getGainOnFeed();
            return new HappinessRules(min, max, defaultValue, feedGain);
        }
        return null;
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
