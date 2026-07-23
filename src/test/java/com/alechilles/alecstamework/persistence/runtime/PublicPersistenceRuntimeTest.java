package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.identity.CompanionAliasLiveBoundary;
import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.CompanionLifecycle;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleLocation;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.companion.lifecycle.ReconciliationGeneration;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.persistence.control.PersistenceEngineLease;
import com.alechilles.alecstamework.persistence.control.PersistenceEngineLineage;
import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.migration.PublicPersistenceTarget;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end startup, evidence deferral, rollback, and shutdown tests. */
class PublicPersistenceRuntimeTest {
    @TempDir
    Path tempDir;

    @Test
    void freshRuntimePublishesOneLineageAndShutsDownCleanly() {
        PublicPersistenceRuntime runtime = runtime(
                PublicPersistenceWorldReconciliation.alreadyComplete()
        );

        assertTrue(runtime.start().toCompletableFuture().join().complete());
        assertEquals(
                PublicPersistenceTarget.Origin.FRESH,
                runtime.targetOrigin().orElseThrow()
        );
        assertTrue(runtime.databasePath().orElseThrow().toFile().isFile());

        assertEquals(
                PublicPersistenceShutdownReport.Status.COMPLETE,
                runtime.shutdown(Duration.ofSeconds(5)).status()
        );
        try (PersistenceEngineLease reopened =
                     PersistenceEngineLease.acquireReplacement(tempDir)) {
            var manifest = reopened.manifest().orElseThrow();
            assertEquals(
                    PersistenceEngineLineage.REPLACEMENT,
                    manifest.lineage()
            );
            assertTrue(manifest.startupComplete());
            assertTrue(manifest.cleanShutdown());
        }
    }

    @Test
    void deferredWorldEvidenceResumesTheSameGraph() {
        AtomicInteger attempts = new AtomicInteger();
        PublicPersistenceWorldReconciliation world =
                new PublicPersistenceWorldReconciliation() {
                    @Override
                    public CompletionStage<Result> awaitEvidence() {
                        return CompletableFuture.completedFuture(
                                attempts.getAndIncrement() == 0
                                        ? Result.DEFERRED
                                        : Result.COMPLETE
                        );
                    }

                    @Override
                    public CompletionStage<Result> reconcile() {
                        return CompletableFuture.completedFuture(
                                Result.COMPLETE
                        );
                    }

                    @Override
                    public void quiesce() {
                    }
                };
        PublicPersistenceRuntime runtime = runtime(world);

        var deferred = runtime.start().toCompletableFuture().join();
        assertEquals(
                PersistenceStartupNode.WAIT_WORLD_EVIDENCE,
                deferred.deferredNode()
        );
        assertEquals(
                PersistenceReadinessLevel.WORLD_EVIDENCE_PENDING,
                deferred.readiness()
        );
        assertThrows(
                IllegalStateException.class,
                () -> runtime.operations().mutateProfile(
                        OperationId.create(),
                        new IdempotencyKey("not-ready"),
                        profileCreate()
                )
        );
        assertTrue(runtime.start().toCompletableFuture().join().complete());
        var submitted = runtime.operations().mutateProfile(
                OperationId.create(),
                new IdempotencyKey("profile-create"),
                profileCreate()
        );
        assertTrue(submitted.accepted());
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                submitted.completion().toCompletableFuture().join().status()
        );
        assertInstanceOf(
                PersistenceReadResult.Found.class,
                runtime.queries().findProfile(profileId())
                        .toCompletableFuture().join()
        );
        assertEquals(2, attempts.get());
        runtime.close();
    }

    @Test
    void failedStartupReleasesWithoutSelectingReplacementLineage() {
        PublicPersistenceWorldReconciliation failed =
                new PublicPersistenceWorldReconciliation() {
                    @Override
                    public CompletionStage<Result> awaitEvidence() {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("injected")
                        );
                    }

                    @Override
                    public CompletionStage<Result> reconcile() {
                        return CompletableFuture.completedFuture(
                                Result.COMPLETE
                        );
                    }

                    @Override
                    public void quiesce() {
                    }
                };
        PublicPersistenceRuntime runtime = runtime(failed);

        var report = runtime.start().toCompletableFuture().join();
        assertEquals(
                PersistenceStartupNode.WAIT_WORLD_EVIDENCE,
                report.failedNode()
        );
        assertFalse(report.complete());
        assertTrue(runtime.shutdown(Duration.ofSeconds(5)).terminal());

        try (PersistenceEngineLease legacy =
                     PersistenceEngineLease.acquireLegacy(tempDir)) {
            assertEquals(
                    PersistenceEngineLineage.LEGACY_PUBLIC,
                    legacy.requestedLineage()
            );
        }
    }

    @Test
    void shutdownKeepsControlLeaseUntilAcceptedWorkflowFinishes()
            throws Exception {
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        PublicPersistenceRuntime runtime = runtime(
                PublicPersistenceWorldReconciliation.alreadyComplete(),
                event -> {
                    projectionEntered.countDown();
                    try {
                        releaseProjection.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
        );
        assertTrue(runtime.start().toCompletableFuture().join().complete());
        var submitted = runtime.operations().mutateProfile(
                OperationId.create(),
                new IdempotencyKey("drain-profile-create"),
                profileCreate()
        );
        assertTrue(projectionEntered.await(5, TimeUnit.SECONDS));

        var timedOut = runtime.shutdown(Duration.ZERO);

        assertEquals(
                PublicPersistenceShutdownReport.Status
                        .FEATURE_DRAIN_TIMED_OUT,
                timedOut.status()
        );
        assertEquals(1, timedOut.outstandingWorkflows());
        assertThrows(
                IllegalStateException.class,
                () -> PersistenceEngineLease.acquireReplacement(tempDir)
        );

        releaseProjection.countDown();
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                submitted.completion().toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).status()
        );
        assertEquals(
                PublicPersistenceShutdownReport.Status.COMPLETE,
                runtime.shutdown(Duration.ofSeconds(5)).status()
        );
    }

    private PublicPersistenceRuntime runtime(
            PublicPersistenceWorldReconciliation world
    ) {
        return runtime(world, event -> {
        });
    }

    private PublicPersistenceRuntime runtime(
            PublicPersistenceWorldReconciliation world,
            java.util.function.Consumer<
                    com.alechilles.alecstamework.api.NpcProfileChangedEvent
                    > profileListener
    ) {
        return new PublicPersistenceRuntime(
                new PublicPersistenceRuntimeConfiguration(
                        tempDir,
                        "runtime-test",
                        () -> -100,
                        (claim, operation) -> LiveOperationResult
                                .confirmed("refund_confirmed")
                                .completed(),
                        profileListener,
                        boundaries(),
                        world,
                        Duration.ofSeconds(5)
                )
        );
    }

    private PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (rotation, operation) ->
                        CompanionAliasLiveBoundary.Result.confirmed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("capture_confirmed").completed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("restoration_confirmed").completed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("coop_capture_confirmed").completed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("coop_release_confirmed").completed()
        );
    }

    private CompanionProfileMutation.Create profileCreate() {
        ProfileId profileId = profileId();
        String metadata = "{\"source\":\"runtime-test\"}";
        CompanionIdentity identity = new CompanionIdentity(
                profileId,
                "Companion",
                "role",
                metadata,
                Sha256Hash.ofUtf8(metadata),
                "world",
                -200,
                -200,
                -200,
                0
        );
        CompanionLifecycle lifecycle = new CompanionLifecycle(
                profileId,
                OwnerId.parse("10000000-0000-0000-0000-000000000001"),
                LifecycleState.UNLOADED,
                LifecycleLocation.none(),
                LifecycleRevision.INITIAL,
                null,
                -200,
                ReconciliationGeneration.INITIAL,
                null
        );
        return new CompanionProfileMutation.Create(
                identity,
                lifecycle,
                java.util.List.of(),
                -200
        );
    }

    private ProfileId profileId() {
        return ProfileId.parse(
                "20000000-0000-0000-0000-000000000001"
        );
    }
}
