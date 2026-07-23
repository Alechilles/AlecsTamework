package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.identity.CompanionAliasLiveBoundary;
import com.alechilles.alecstamework.persistence.control.PersistenceEngineLease;
import com.alechilles.alecstamework.persistence.control.PersistenceEngineLineage;
import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.migration.PublicPersistenceTarget;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(runtime.start().toCompletableFuture().join().complete());
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

    private PublicPersistenceRuntime runtime(
            PublicPersistenceWorldReconciliation world
    ) {
        return new PublicPersistenceRuntime(
                new PublicPersistenceRuntimeConfiguration(
                        tempDir,
                        "runtime-test",
                        () -> -100,
                        (claim, operation) -> LiveOperationResult
                                .confirmed("refund_confirmed")
                                .completed(),
                        event -> {
                        },
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
}
