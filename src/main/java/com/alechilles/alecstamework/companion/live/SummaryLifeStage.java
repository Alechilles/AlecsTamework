package com.alechilles.alecstamework.companion.live;

import com.alechilles.alecstamework.companion.index.CompanionSummary;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import javax.annotation.Nullable;

/**
 * Rebuilds a detached life-stage component from a record summary's progression, so code without
 * the body (the saved panel, coop production) can use the shared progression math.
 */
public final class SummaryLifeStage {
    private SummaryLifeStage() {
    }

    /** A new component holding the summary's progression values, or null when there are none. */
    @Nullable
    public static TameworkLifeStageComponent of(@Nullable CompanionSummary.Progression p) {
        if (p == null) {
            return null;
        }
        TameworkLifeStageComponent state = new TameworkLifeStageComponent();
        state.setStage(p.stage());
        state.setBornAtMs(p.bornAtMs());
        state.setAdolescentAtMs(p.adolescentAtMs());
        state.setAdultAtMs(p.adultAtMs());
        state.setGrowthScalingEnabled(p.growthScalingEnabled());
        state.setAgeProgressMs(p.ageProgressMs());
        state.setProgressionOwnerId(p.progressionOwnerId());
        state.setProgressionClockMs(p.progressionClockMs());
        state.setProgressionInitialized(p.progressionInitialized());
        state.setLastProgressionWorldMs(p.lastProgressionWorldMs());
        state.setLifecycleNowMs(p.lifecycleNowMs());
        state.setJuvenileClockInitialized(p.juvenileClockInitialized());
        state.setStoredProgressionPaused(p.progressionPaused());
        state.setActiveProgressMs(p.activeProgressMs());
        return state;
    }
}
