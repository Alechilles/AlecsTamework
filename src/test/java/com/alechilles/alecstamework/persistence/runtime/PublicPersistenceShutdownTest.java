package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.persistence.control.PersistenceEngineLease;
import com.alechilles.alecstamework.persistence.control
        .PersistenceStartupCoordinator;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for best-effort, one-owner persistence teardown. */
class PublicPersistenceShutdownTest {
    @TempDir
    Path tempDir;

    @Test
    void quiesceFailureDoesNotSkipKernelOrLeaseTeardown() {
        PublicPersistenceRuntime runtime = new PublicPersistenceRuntime(
                configuration(new ThrowingQuiesce())
        );
        assertTrue(runtime.start().toCompletableFuture().join().complete());

        PublicPersistenceShutdownReport report =
                runtime.shutdown(Duration.ofSeconds(5));

        assertEquals(
                PublicPersistenceShutdownReport.Status.QUIESCE_FAILED,
                report.status()
        );
        assertTrue(report.terminal());
        assertNotNull(report.kernel());
        assertTrue(report.kernel().clean());
        assertEquals("injected_quiesce", report.failure().getMessage());
        assertLeaseCanBeReacquired();
    }

    @Test
    void workflowTimeoutDefersKernelAndLeaseUntilWorkflowCompletes() {
        PublicPersistenceWorkflowTracker workflows =
                new PublicPersistenceWorkflowTracker();
        var registry = PublicPersistenceFeatureRegistry.create();
        var state = new PublicPersistenceRuntimeState(
                configuration(
                        PublicPersistenceWorldReconciliation.alreadyComplete()
                ),
                registry,
                workflows
        );
        var startup = new PersistenceStartupCoordinator(
                registry,
                state.actions()
        );
        state.bind(startup);
        assertTrue(startup.advance().toCompletableFuture().join().complete());
        CompletableFuture<Void> acceptedWorkflow = new CompletableFuture<>();
        workflows.track(acceptedWorkflow);

        PublicPersistenceShutdownReport report =
                state.shutdown(Duration.ZERO);

        assertEquals(
                PublicPersistenceShutdownReport.Status
                        .FEATURE_DRAIN_TIMED_OUT,
                report.status()
        );
        assertEquals(1, report.outstandingWorkflows());
        assertFalse(report.terminal());
        assertNull(report.kernel());
        assertThrows(
                IllegalStateException.class,
                () -> PersistenceEngineLease.acquireReplacement(tempDir)
        );

        acceptedWorkflow.complete(null);
        PublicPersistenceShutdownReport resumed =
                state.shutdown(Duration.ofSeconds(5));

        assertEquals(
                PublicPersistenceShutdownReport.Status.COMPLETE,
                resumed.status()
        );
        assertTrue(resumed.terminal());
        assertNotNull(resumed.kernel());
        assertTrue(resumed.kernel().clean());
        assertLeaseCanBeReacquired();
    }

    private PublicPersistenceRuntimeConfiguration configuration(
            PublicPersistenceWorldReconciliation world
    ) {
        return new PublicPersistenceRuntimeConfiguration(
                tempDir,
                "shutdown-regression",
                () -> -100,
                (claim, operation) -> LiveOperationResult
                        .confirmed("refund_confirmed").completed(),
                ignored -> {
                },
                boundaries(),
                world,
                Duration.ofSeconds(5)
        );
    }

    private PublicPersistenceLiveBoundaries boundaries() {
        return new PublicPersistenceLiveBoundaries(
                (request, operation) -> confirmed("capture"),
                (request, operation) -> confirmed("capture_release"),
                (request, operation) -> confirmed("restoration"),
                (request, operation) -> confirmed("coop_capture"),
                (request, operation) -> confirmed("coop_release")
        );
    }

    private CompletionStage<LiveOperationResult> confirmed(String receipt) {
        return LiveOperationResult.confirmed(receipt).completed();
    }

    private void assertLeaseCanBeReacquired() {
        try (PersistenceEngineLease ignored =
                     PersistenceEngineLease.acquireReplacement(tempDir)) {
            assertNotNull(ignored);
        }
    }

    private static final class ThrowingQuiesce
            implements PublicPersistenceWorldReconciliation {
        @Override
        public CompletionStage<Result> awaitEvidence() {
            return CompletableFuture.completedFuture(Result.COMPLETE);
        }

        @Override
        public CompletionStage<Result> reconcile() {
            return CompletableFuture.completedFuture(Result.COMPLETE);
        }

        @Override
        public void quiesce() {
            throw new IllegalStateException("injected_quiesce");
        }
    }
}
