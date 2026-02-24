package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;

/**
 * Applies breeding happiness progression updates from gameplay events.
 */
public final class BreedingHappinessService {
    private static final double EPSILON = 0.000001;

    private BreedingHappinessService() {
    }

    public static boolean applyFeedGain(@Nullable Ref<EntityStore> npcRef, @Nullable Store<EntityStore> store) {
        if (npcRef == null || store == null || !npcRef.isValid()) {
            return false;
        }
        ComponentType<EntityStore, TameworkBreedingComponent> type = TameworkBreedingComponent.getComponentType();
        if (type == null) {
            return false;
        }
        TameworkBreedingComponent breeding = store.getComponent(npcRef, type);
        if (breeding == null) {
            return false;
        }
        TwBreedingConfig config = BreedingConfigResolver.resolveConfig(npcRef, store, breeding);
        if (config == null || !config.isEnabled()) {
            return false;
        }
        boolean changed = false;
        if ((breeding.getConfigId() == null || breeding.getConfigId().isBlank())
                && config.getId() != null
                && !config.getId().isBlank()) {
            breeding.setConfigId(config.getId());
            changed = true;
        }
        TwBreedingConfig.HappinessSettings settings = config.getHappiness();
        double min = settings.getMin();
        double max = settings.getMax();
        if (max < min) {
            double swap = min;
            min = max;
            max = swap;
        }
        double gain = settings.getGainOnFeed();
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
        double previous = breeding.getHappiness();
        double next = clamp(previous + adjustedGain, min, max);
        if (Math.abs(next - previous) > EPSILON) {
            breeding.setHappiness(next);
            changed = true;
        }
        boolean ready = next >= settings.getThreshold();
        if (breeding.isReady() != ready) {
            breeding.setReady(ready);
            changed = true;
        }
        if (changed) {
            breeding.setLastHappinessUpdateMs(System.currentTimeMillis());
            store.putComponent(npcRef, type, breeding);
        }
        return changed;
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
