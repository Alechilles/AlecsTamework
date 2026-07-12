package com.alechilles.alecstamework.npc.actions;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreedingSpawnCompletionGuardTest {
    @Test
    void followUpFailureCannotReclassifyAnAlreadyCreatedChildAsFailed() {
        BreedingSpawnCompletionGuard guard = new BreedingSpawnCompletionGuard();
        List<Throwable> failures = new ArrayList<>();
        RuntimeException failure = new IllegalStateException("progression failed");

        boolean spawned = guard.complete(() -> {
            throw failure;
        }, failures::add);

        assertTrue(spawned);
        assertEquals(List.of(failure), failures);
    }

    @Test
    void linkageFailureAfterAddCannotRevokeSpawnSuccess() {
        BreedingSpawnCompletionGuard guard = new BreedingSpawnCompletionGuard();
        List<Throwable> failures = new ArrayList<>();
        LinkageError failure = new LinkageError("optional integration disappeared");

        boolean spawned = guard.complete(() -> {
            throw failure;
        }, failures::add);

        assertTrue(spawned);
        assertEquals(List.of(failure), failures);
    }

    @Test
    void reportingFailureAlsoCannotRevokeSpawnSuccess() {
        BreedingSpawnCompletionGuard guard = new BreedingSpawnCompletionGuard();

        boolean spawned = guard.complete(
                () -> {
                    throw new IllegalStateException("progression failed");
                },
                ignored -> {
                    throw new IllegalStateException("logger failed");
                });

        assertTrue(spawned);
    }
}
