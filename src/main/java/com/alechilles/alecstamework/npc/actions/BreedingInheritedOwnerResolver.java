package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves the inherited owner for a child spawned from the current live pair. */
final class BreedingInheritedOwnerResolver {
    private BreedingInheritedOwnerResolver() {
    }

    @Nonnull
    static BreedingOffspringProgressionService.OwnerSnapshot resolve(
            @Nullable TwBreedingConfig config,
            @Nullable String childRoleId,
            @Nullable BreedingOffspringProgressionService.OwnerSnapshot parentAOwner,
            @Nullable BreedingOffspringProgressionService.OwnerSnapshot parentBOwner
    ) {
        TwBreedingConfig.InheritanceSettings inheritance = config != null
                ? config.resolveInheritance(childRoleId)
                : null;
        if (inheritance != null && !inheritance.isInheritOwner()) {
            return BreedingOffspringProgressionService.OwnerSnapshot.empty();
        }
        if (parentAOwner != null && parentAOwner.ownerId() != null) {
            return parentAOwner;
        }
        if (parentBOwner != null && parentBOwner.ownerId() != null) {
            return parentBOwner;
        }
        return BreedingOffspringProgressionService.OwnerSnapshot.empty();
    }
}
