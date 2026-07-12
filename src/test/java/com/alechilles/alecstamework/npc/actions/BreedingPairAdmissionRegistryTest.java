package com.alechilles.alecstamework.npc.actions;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Covers shared manual/passive parent contention and duplicate spawn claims. */
class BreedingPairAdmissionRegistryTest {
    @Test
    void eitherParentBlocksASecondConcurrentPairingUntilCompletion() {
        BreedingPairAdmissionRegistry registry = BreedingPairAdmissionRegistry.shared();
        UUID parentA = UUID.randomUUID();
        UUID parentB = UUID.randomUUID();
        UUID parentC = UUID.randomUUID();
        BreedingPairAdmissionRegistry.Token first = registry.tryReserve(parentA, parentB);
        assertNotNull(first);
        try {
            assertNull(registry.tryReserve(parentB, parentC));
            assertTrue(registry.claimSpawn(first));
            assertFalse(registry.claimSpawn(first));
        } finally {
            registry.complete(first);
        }
        BreedingPairAdmissionRegistry.Token second = registry.tryReserve(parentB, parentC);
        assertNotNull(second);
        registry.cancel(second);
    }

    @Test
    void persistedCooldownGenerationProducesStableRestartAttemptKey() {
        BreedingPairAdmissionRegistry registry = BreedingPairAdmissionRegistry.shared();
        UUID parentA = UUID.randomUUID();
        UUID parentB = UUID.randomUUID();
        BreedingPairAdmissionRegistry.Token first = registry.tryReserve(
                parentA, parentB, -1200L, 4500L
        );
        assertNotNull(first);
        registry.complete(first);

        BreedingPairAdmissionRegistry.Token replay = registry.tryReserve(
                parentB, parentA, 4500L, -1200L
        );
        assertNotNull(replay);
        assertEquals(first.jobId(), replay.jobId());
        registry.complete(replay);

        BreedingPairAdmissionRegistry.Token nextGeneration = registry.tryReserve(
                parentA, parentB, 9000L, 4500L
        );
        assertNotNull(nextGeneration);
        assertNotEquals(first.jobId(), nextGeneration.jobId());
        registry.complete(nextGeneration);
    }
}
