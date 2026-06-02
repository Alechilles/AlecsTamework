package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;

/**
 * Caches prompt classification for the current interaction config instance.
 */
final class InteractionPromptPlanCache {
    private TwInteractionConfig cachedConfig;
    private TwInteractionConfig.InteractionEntry[] cachedEntries;
    private InteractionPromptPlan cachedPlan;

    InteractionPromptPlan getOrBuild(TwInteractionConfig config, InteractionPromptPlanBuilder builder) {
        TwInteractionConfig.InteractionEntry[] entries = config != null ? config.getInteractions() : null;
        if (config == cachedConfig && entries == cachedEntries && cachedPlan != null) {
            return cachedPlan;
        }
        cachedConfig = config;
        cachedEntries = entries;
        cachedPlan = builder.build(entries);
        return cachedPlan;
    }

    interface InteractionPromptPlanBuilder {
        InteractionPromptPlan build(TwInteractionConfig.InteractionEntry[] entries);
    }
}
