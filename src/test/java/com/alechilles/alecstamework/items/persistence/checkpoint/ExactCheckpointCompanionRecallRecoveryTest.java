package com.alechilles.alecstamework.items.persistence.checkpoint;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards source-section load outcomes for exact-checkpoint Recall recovery. */
class ExactCheckpointCompanionRecallRecoveryTest {

    @Test
    void cleanMissingSectionContinuesRecoveryWhileSourceWorldIsAlive() {
        // Regression: the 2026-09-01 live report returned a clean null section.
        CompletableFuture<Object> missingSection =
                CompletableFuture.completedFuture(null);

        boolean loaded = ExactCheckpointCompanionRecallRecovery
                .sourceProbeCompleted(missingSection, () -> true)
                .toCompletableFuture()
                .join();

        assertTrue(loaded);
    }

    @Test
    void stoppedWorldOrFailedSectionLoadStopsRecovery() {
        CompletableFuture<Object> missingSection =
                CompletableFuture.completedFuture(null);
        CompletableFuture<Object> failedSection =
                CompletableFuture.failedFuture(
                        new IllegalStateException("section load failed")
                );

        boolean stoppedWorld = ExactCheckpointCompanionRecallRecovery
                .sourceProbeCompleted(missingSection, () -> false)
                .toCompletableFuture()
                .join();
        boolean failedLoad = ExactCheckpointCompanionRecallRecovery
                .sourceProbeCompleted(failedSection, () -> true)
                .toCompletableFuture()
                .join();

        assertFalse(stoppedWorld);
        assertFalse(failedLoad);
    }
}
