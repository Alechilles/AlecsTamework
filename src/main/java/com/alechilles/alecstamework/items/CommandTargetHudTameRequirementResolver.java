package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.progression.TranquilizerStackDisplayService;
import javax.annotation.Nullable;

/** Resolves tame requirement rows shown in the command target HUD. */
final class CommandTargetHudTameRequirementResolver {
    static final String TRANQUILIZER_EFFECT_ID = "Tw_Status_Tranquilized";

    @Nullable
    CommandTargetHudViewModel.TameRequirementRow fromRequiredRemainingSeconds(double requiredRemainingSeconds,
                                                                              @Nullable String currentStacksText) {
        int requiredStacks = TranquilizerStackDisplayService.computeStacks(requiredRemainingSeconds);
        if (requiredStacks <= 0) {
            return null;
        }
        return new CommandTargetHudViewModel.TameRequirementRow(true, requiredStacks, currentStacksText);
    }
}
