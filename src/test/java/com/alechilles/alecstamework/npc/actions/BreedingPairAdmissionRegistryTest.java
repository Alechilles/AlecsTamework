package com.alechilles.alecstamework.npc.actions;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Regression coverage for overlapping delayed breeding callbacks. */
class BreedingPairAdmissionRegistryTest {

    @Test
    void parentCannotJoinAnotherPairUntilScheduledBirthReleasesIt() {
        BreedingPairAdmissionRegistry registry = new BreedingPairAdmissionRegistry();
        Object worldStore = new Object();
        UUID parentA = UUID.randomUUID();
        UUID parentB = UUID.randomUUID();
        UUID parentC = UUID.randomUUID();

        BreedingPairAdmissionRegistry.Lease first = registry.tryAcquire(
                worldStore, parentA, parentB
        );

        assertNotNull(first);
        assertNull(registry.tryAcquire(worldStore, parentB, parentC));

        first.close();

        BreedingPairAdmissionRegistry.Lease second = registry.tryAcquire(
                worldStore, parentB, parentC
        );
        assertNotNull(second);
        second.close();
    }
}
