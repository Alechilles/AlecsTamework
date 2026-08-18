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

        long nextDelayMs = NeedsSweepIntervalPolicy.intervalMs(component, config, baseIntervalMs);
        boolean suppressionActive = CompanionNeedsService.requiresFrequentNaturalRegenSuppressionTick(
                component,
                config
        );
        boolean needsDamageActive = CompanionNeedsService.isNeedsDamageActive(npcRef, store, roleId);
        return new Outcome(nextDelayMs, suppressionActive, needsDamageActive);
    }

    static Outcome outcomeForRatiosForTests(double hungerRatio,
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
    private static Outcome retryOutcome() {
        return new Outcome(CONFIG_RETRY_DELAY_MS, false, false);
    }

    /** The next delay and active state produced by one scheduled update. */
    public record Outcome(long nextDelayMs, boolean suppressionActive, boolean needsDamageActive) {
    }
}
