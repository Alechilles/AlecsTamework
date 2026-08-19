package com.alechilles.alecstamework.npc.progression;

import com.alechilles.alecstamework.config.assets.TwNeedsConfig;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runs one queued needs update and reports the state needed to schedule the next update.
 */
public final class CompanionNeedsScheduledUpdate {
    private static final long CONFIG_RETRY_DELAY_MS = 30_000L;

    private CompanionNeedsScheduledUpdate() {
    }

    /**
     * Runs one needs update on the current world thread.
     *
     * <p>The callback carries only the current entity reference and store. It does not retain
     * either value after returning.</p>
     */
    @Nonnull
    public static Outcome run(@Nullable Ref<EntityStore> npcRef,
                              @Nullable Store<EntityStore> store,
                              @Nullable String roleId,
                              long baseIntervalMs) {
        CompanionNeedsService.tickScheduledNeeds(npcRef, store, roleId);

        if (npcRef == null || store == null || !npcRef.isValid()) {
            return retryOutcome();
        }
        ComponentType<EntityStore, TameworkNeedsComponent> needsType = TameworkNeedsComponent.getComponentType();
        if (needsType == null) {
            return retryOutcome();
        }
        TameworkNeedsComponent component = store.getComponent(npcRef, needsType);
        TwNeedsConfig config = CompanionNeedsService.resolveNeedsConfig(npcRef, store, roleId, component);
        if (component == null || !CompanionNeedsRuntimePolicy.isNeedsEnabled(config)) {
            return retryOutcome();
        }

        boolean suppressionActive = CompanionNeedsService.requiresFrequentNaturalRegenSuppressionTick(
                component,
                config
        );
        boolean needsDamageActive = CompanionNeedsService.isNeedsDamageActive(npcRef, store, roleId);
        TwNeedsConfig.ValueSettings values = config.getValues();
        return outcomeForRatios(
                resolveRatio(component.getHunger(), values.getHungerMin(), values.getHungerMax()),
                resolveRatio(component.getThirst(), values.getThirstMin(), values.getThirstMax()),
                suppressionActive,
                needsDamageActive,
                baseIntervalMs
        );
    }

    static Outcome outcomeForRatiosForTests(double hungerRatio,
                                            double thirstRatio,
                                            boolean suppressionActive,
                                            boolean needsDamageActive,
                                            long baseIntervalMs) {
        return outcomeForRatios(
                hungerRatio,
                thirstRatio,
                suppressionActive,
                needsDamageActive,
                baseIntervalMs
        );
    }

    static Outcome outcomeForRatios(double hungerRatio,
                                    double thirstRatio,
                                    boolean suppressionActive,
                                    boolean needsDamageActive,
                                    long baseIntervalMs) {
        long nextDelayMs = NeedsSweepIntervalPolicy.intervalMsForRatios(
                hungerRatio,
                thirstRatio,
                baseIntervalMs
        );
        return new Outcome(nextDelayMs, suppressionActive, needsDamageActive);
    }

    @Nonnull
    static Outcome retryOutcome() {
        return new Outcome(CONFIG_RETRY_DELAY_MS, false, false);
    }

    private static double resolveRatio(double value, double min, double max) {
        if (!Double.isFinite(value) || !Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
            return 0.0;
        }
        double ratio = (value - min) / (max - min);
        if (!Double.isFinite(ratio)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, ratio));
    }

    /** The next delay and active state produced by one scheduled update. */
    public record Outcome(long nextDelayMs, boolean suppressionActive, boolean needsDamageActive) {
    }
}
