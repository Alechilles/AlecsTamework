package com.alechilles.alecstamework.npc.breeding;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for capture cancellation racing asynchronous population preparation. */
class BreedingPreparedCancellationCoordinatorTest {
    @Test
    void cancellationWaitsForLateCapabilityAndItsDurableTerminalResult() throws Exception {
        BreedingPreparedCancellationCoordinator coordinator =
                new BreedingPreparedCancellationCoordinator();
        Object scope = new Object();
        UUID jobId = UUID.randomUUID();
        BreedingParentIdentity first = parent(1L, "profile-a");
        BreedingParentIdentity second = parent(2L, "profile-b");
        CompletableFuture<Boolean> capability = new CompletableFuture<>();
        AtomicInteger cancellations = new AtomicInteger();

        assertTrue(coordinator.beginPreparation(scope, jobId, first, second));
        CompletableFuture<Boolean> durable = coordinator.cancelDurablyByParent(
                scope, first.entityUuid(), null, "parent-captured"
        );
        assertTrue(durable != null);
        assertFalse(durable.isDone());

        coordinator.registerCapability(scope, jobId, reason -> {
            cancellations.incrementAndGet();
            return capability;
        });
        coordinator.finishPreparation(scope, jobId);

        assertFalse(durable.isDone());
        assertEquals(1, cancellations.get());
        capability.complete(true);
        assertTrue(durable.get(1, TimeUnit.SECONDS));

        UUID laterJob = UUID.randomUUID();
        assertFalse(coordinator.beginPreparation(
                scope, laterJob, first, second
        ));
        coordinator.releaseParentFence(
                scope, first.entityUuid(), null, false
        );
        assertTrue(coordinator.beginPreparation(
                scope, laterJob, first, second
        ));
    }

    @Test
    void failedRegistrationPathCanNeverOpenCaptureGate() throws Exception {
        BreedingPreparedCancellationCoordinator coordinator =
                new BreedingPreparedCancellationCoordinator();
        Object scope = new Object();
        UUID jobId = UUID.randomUUID();

        assertTrue(coordinator.beginPreparation(scope, jobId));
        CompletableFuture<Boolean> durable = coordinator.cancelDurably(
                scope, jobId, "parent-captured"
        );
        coordinator.failPreparation(scope, jobId);

        assertFalse(durable.get(1, TimeUnit.SECONDS));
        assertFalse(coordinator.beginPreparation(scope, jobId));
    }

    @Test
    void failedPriorPreparationRejectsANewJobForEitherParentIdentity() {
        BreedingPreparedCancellationCoordinator coordinator =
                new BreedingPreparedCancellationCoordinator();
        Object scope = new Object();
        BreedingParentIdentity first = parent(7L, "profile-a");
        BreedingParentIdentity second = parent(8L, "profile-b");
        UUID failedJob = UUID.randomUUID();

        assertTrue(coordinator.beginPreparation(
                scope, failedJob, first, second
        ));
        coordinator.failPreparation(scope, failedJob);

        assertFalse(coordinator.beginPreparation(
                scope,
                UUID.randomUUID(),
                parent(70L, first.profileId()),
                parent(9L, "profile-c")
        ));
    }

    @Test
    void retainedFailedGateBlocksRemappedProfileAndCapture() throws Exception {
        BreedingPreparedCancellationCoordinator coordinator =
                new BreedingPreparedCancellationCoordinator();
        Object scope = new Object();
        BreedingParentIdentity first = parent(11L, "profile-a");
        BreedingParentIdentity initialPartner = parent(12L, "profile-b");
        UUID failedJob = UUID.randomUUID();
        assertTrue(coordinator.beginPreparation(
                scope, failedJob, first, initialPartner
        ));
        coordinator.failPreparation(scope, failedJob);

        BreedingParentIdentity laterIdentity = parent(13L, "profile-a");
        BreedingParentIdentity laterPartner = parent(14L, "profile-c");
        UUID successfulJob = UUID.randomUUID();
        assertFalse(coordinator.beginPreparation(
                scope, successfulJob, laterIdentity, laterPartner
        ));

        CompletableFuture<Boolean> byProfile = coordinator.cancelDurablyByParent(
                scope, UUID.randomUUID(), "profile-a", "parent-captured"
        );
        assertTrue(byProfile != null);
        assertFalse(byProfile.get(1, TimeUnit.SECONDS));
        assertTrue(coordinator.cancelDurablyByParent(
                scope, UUID.randomUUID(), "profile-missing", "parent-captured"
        ) == null);
    }

    @Test
    void safeNoGateCaptureFenceBlocksOnlyUntilExplicitFailedAttemptRelease() {
        BreedingPreparedCancellationCoordinator coordinator =
                new BreedingPreparedCancellationCoordinator();
        Object scope = new Object();
        BreedingParentIdentity first = parent(21L, "profile-a");
        BreedingParentIdentity second = parent(22L, "profile-b");

        assertTrue(coordinator.cancelDurablyByParent(
                scope, first.entityUuid(), first.profileId(), "parent-captured"
        ) == null);
        assertFalse(coordinator.beginPreparation(
                scope, UUID.randomUUID(), first, second
        ));

        coordinator.releaseParentFence(
                scope, first.entityUuid(), first.profileId(), false
        );

        assertTrue(coordinator.beginPreparation(
                scope, UUID.randomUUID(), first, second
        ));
    }

    @Test
    void missingOrThrowingCapabilityCompletionFailsClosed() throws Exception {
        Object scope = new Object();
        UUID missingJob = UUID.randomUUID();
        BreedingPreparedCancellationCoordinator missing =
                new BreedingPreparedCancellationCoordinator();
        assertTrue(missing.beginPreparation(scope, missingJob));
        missing.registerCapability(scope, missingJob, reason -> null);
        CompletableFuture<Boolean> missingResult = missing.cancelDurably(
                scope, missingJob, "parent-captured"
        );
        missing.finishPreparation(scope, missingJob);
        assertFalse(missingResult.get(1, TimeUnit.SECONDS));

        UUID throwingJob = UUID.randomUUID();
        BreedingPreparedCancellationCoordinator throwing =
                new BreedingPreparedCancellationCoordinator();
        assertTrue(throwing.beginPreparation(scope, throwingJob));
        throwing.registerCapability(scope, throwingJob, reason -> {
            throw new IllegalStateException("simulated cancellation start failure");
        });
        CompletableFuture<Boolean> throwingResult = throwing.cancelDurably(
                scope, throwingJob, "parent-captured"
        );
        throwing.finishPreparation(scope, throwingJob);
        assertFalse(throwingResult.get(1, TimeUnit.SECONDS));
    }

    private static BreedingParentIdentity parent(long value, String profileId) {
        return new BreedingParentIdentity(new UUID(0L, value), profileId);
    }
}
