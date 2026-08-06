package com.alechilles.alecstamework.npc.actions;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for owner changes during delayed breeding effects. */
class BreedingOwnerSnapshotResolverTest {
    @Test
    void sameOwnerPairIsRejectedWhenOneParentChangesOwnerBeforeBirth() {
        UUID originalOwner = UUID.randomUUID();
        BreedingOffspringProgressionService.OwnerSnapshot parentA =
                new BreedingOffspringProgressionService.OwnerSnapshot(
                        originalOwner, "A"
                );
        BreedingOffspringProgressionService.OwnerSnapshot unchangedParentB =
                new BreedingOffspringProgressionService.OwnerSnapshot(
                        originalOwner, "B"
                );
        BreedingOffspringProgressionService.OwnerSnapshot changedParentB =
                new BreedingOffspringProgressionService.OwnerSnapshot(
                        UUID.randomUUID(), "B"
                );

        assertTrue(BreedingOwnerSnapshotResolver.allowsDelayedBirth(
                true, parentA, unchangedParentB
        ));
        assertFalse(BreedingOwnerSnapshotResolver.allowsDelayedBirth(
                true, parentA, changedParentB
        ));
        assertTrue(BreedingOwnerSnapshotResolver.allowsDelayedBirth(
                false, parentA, changedParentB
        ));
    }
}
