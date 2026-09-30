package com.alechilles.alecstamework.companion.index;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What UI shows for a companion that is not loaded (spec 6.6). Refreshed when a
 * snapshot is taken and on unload; UI never decodes snapshots to get these values.
 */
public record CompanionSummary(
        @Nullable String displayName,
        @Nullable String roleId,
        @Nullable String iconId,
        int level,
        float healthFraction,
        @Nullable String lifeStage,
        int happinessBand,
        int needsBand,
        @Nullable String commandState,
        boolean breedingPending
) {
    @Nonnull
    public static final CompanionSummary EMPTY = new CompanionSummary(null, null, null, 0, 1f, null, 0, 0, null, false);
}
