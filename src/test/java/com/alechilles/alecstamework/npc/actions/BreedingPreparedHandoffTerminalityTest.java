package com.alechilles.alecstamework.npc.actions;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for prepared-litter leaks after pre-spawn callback exceptions. */
class BreedingPreparedHandoffTerminalityTest {
    @Test
    void cleanupAttemptsEveryResourceExactlyOnceWhenPopulationCancellationThrows() {
        AtomicInteger population = new AtomicInteger();
        AtomicInteger parentCompletion = new AtomicInteger();
        AtomicInteger nearby = new AtomicInteger();
        AtomicInteger pair = new AtomicInteger();
        AtomicInteger warnings = new AtomicInteger();
        BreedingPreparedHandoffTerminality terminality = new BreedingPreparedHandoffTerminality(
                reason -> {
                    population.incrementAndGet();
                    throw new IllegalStateException("simulated population failure");
                },
                () -> {
                    parentCompletion.incrementAndGet();
                    throw new IllegalStateException("simulated parent completion failure");
                },
                nearby::incrementAndGet,
                pair::incrementAndGet,
                ignored -> warnings.incrementAndGet()
        );

        terminality.cancel("world-callback-failed");
        terminality.cancel("duplicate-callback");

        assertEquals(1, population.get());
        assertEquals(1, parentCompletion.get());
        assertEquals(1, nearby.get());
        assertEquals(1, pair.get());
        assertEquals(2, warnings.get());
        assertFalse(terminality.transferToSpawn());
    }

    @Test
    void transferredBatchCannotBeCanceledByLatePairingCallback() {
        AtomicInteger population = new AtomicInteger();
        AtomicInteger nearby = new AtomicInteger();
        AtomicInteger pair = new AtomicInteger();
        BreedingPreparedHandoffTerminality terminality = new BreedingPreparedHandoffTerminality(
                ignored -> population.incrementAndGet(),
                nearby::incrementAndGet,
                pair::incrementAndGet,
                ignored -> { }
        );

        assertTrue(terminality.transferToSpawn());
        terminality.cancel("late-canceled-action");

        assertEquals(0, population.get());
        assertEquals(0, nearby.get());
        assertEquals(0, pair.get());
    }
}
