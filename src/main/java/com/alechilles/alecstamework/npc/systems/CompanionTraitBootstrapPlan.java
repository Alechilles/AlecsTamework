package com.alechilles.alecstamework.npc.systems;

import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import javax.annotation.Nullable;

/** Selects the smallest progression repair required when an NPC loads. */
enum CompanionTraitBootstrapPlan {
    NONE,
    LIFE_STAGE_ONLY,
    FULL_REPAIR;

    static CompanionTraitBootstrapPlan classify(@Nullable TameworkTraitsComponent traits,
                                                @Nullable String configId,
                                                boolean lifeStagePresent) {
        if (!hasValidTraits(traits, configId)) {
            return FULL_REPAIR;
        }
        return lifeStagePresent ? NONE : LIFE_STAGE_ONLY;
    }

    private static boolean hasValidTraits(@Nullable TameworkTraitsComponent traits,
                                          @Nullable String configId) {
        if (traits == null || traits.getRollSeed() == 0L || traits.getTraitValues().length == 0) {
            return false;
        }
        if (configId == null || configId.isBlank()) {
            return true;
        }
        return traits.getConfigId() != null
                && !traits.getConfigId().isBlank()
                && configId.equalsIgnoreCase(traits.getConfigId());
    }
}
