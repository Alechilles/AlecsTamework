package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** One fertility roll and the exact immutable child roles/owners selected from it. */
record BreedingBirthPlan(
        @Nonnull BreedingFertilityOffspringService.FertilityRoll fertility,
        @Nonnull List<PlannedChild> children
) {
    BreedingBirthPlan {
        children = List.copyOf(children);
    }

    record PlannedChild(
            @Nonnull String childKey,
            @Nonnull BreedingResolvedSpawnRole spawnRole,
            @Nonnull BreedingOffspringProgressionService.OwnerSnapshot owner,
            @Nonnull String populationType
    ) {
    }
}
