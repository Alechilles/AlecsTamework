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
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutationDefinition;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteConnectionFactory;
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV1Manager;
import com.alechilles.alecstamework.persistence.control.PersistenceEngineLease;
import com.alechilles.alecstamework.persistence.control.PersistenceEngineLineage;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureCircuitState;
import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import com.alechilles.alecstamework.persistence.migration.PublicPersistenceTarget;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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

        PublicPersistenceOperationalStatus opening =
                runtime.operationalStatus();
        assertEquals(PersistenceEngineMode.NEXT, opening.engine());
        assertEquals(
                PublicPersistenceOperationalStatus.StorageMode.STARTING,
                opening.storageMode()
        );
        assertTrue(opening.databasePath().isEmpty());
        assertTrue(opening.schemaVersion().isEmpty());
        assertTrue(runtime.start().toCompletableFuture().join().complete());
        assertEquals(
                PublicPersistenceTarget.Origin.FRESH,
                runtime.targetOrigin().orElseThrow()
        );
        assertTrue(runtime.databasePath().orElseThrow().toFile().isFile());
        PublicPersistenceMetricsSnapshot metrics = runtime.metrics();
        assertEquals(
                PublicPersistenceFeatureRegistry.create()
                        .descriptors().size(),
                metrics.features().size()
        );
        assertTrue(metrics.readsCompleted() > 0);
        assertNull(metrics.lastGlobalFailureCode());
        PublicPersistenceDiagnosticsSnapshot diagnostics =
                diagnostics(runtime);
        PublicPersistencePerformanceSnapshot performance =
                runtime.performance();
        assertEquals(
                1,
                performance.startupNodes().get(
                        PersistenceStartupNode.OPEN_TARGET
                ).count()
        );
        assertTrue(performance.reads().execution().count() > 0);
        assertTrue(performance.reads().maximumDepth() > 0);
        assertEquals(metrics.features().size(), diagnostics.features().size());
        assertEquals(7, diagnostics.projectionCheckpoints().size());
        assertEquals(0, diagnostics.outboxHead());
        assertTrue(diagnostics.openIncidentsByCode().isEmpty());
        assertTrue(diagnostics.activeQuarantinesByScope().isEmpty());
        assertEquals(0, diagnostics.openCircuitCount());
        assertEquals(
                0L,
                diagnostics.operationsByPhase().values().stream()
                        .mapToLong(Long::longValue).sum()
        );
        for (var descriptor
                : PublicPersistenceFeatureRegistry.create().descriptors()) {
            var health = diagnostics.features().get(
                    descriptor.featureId()
            );
            assertEquals(descriptor.domain(), health.domain());
            assertEquals(
                    descriptor.operationScopes().keySet(),
                    health.operationCounts().keySet()
            );
            assertEquals(
                    descriptor.metricsNamespace(),
                    health.metrics().namespace()
            );
        }

        assertEquals(
                PublicPersistenceShutdownReport.Status.COMPLETE,
                runtime.shutdown(Duration.ofSeconds(5)).status()
        );
        PublicPersistenceOperationalStatus closed =
                runtime.operationalStatus();
        assertEquals(
                PublicPersistenceOperationalStatus.StorageMode.CLOSED,
                closed.storageMode()
        );
        assertEquals(
                PublicPersistenceOperationalStatus.CheckpointEvidence.Status
                        .COMPLETED,
                closed.lastCheckpoint().status()
        );
        assertEquals(
                SqliteSchemaV1Manager.VERSION,
                closed.schemaVersion().orElseThrow()
        );
        assertTrue(closed.guidance().contains(
                "legacy_rollback_requires_complete_pre_cutover_backup_restore"
        ));
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
    void durableBoundedCircuitBlocksOnlyItsDescriptor() throws Exception {
        Path database = PersistenceFiles.replacementDatabase(tempDir);
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        SqliteSchemaV1Manager schemas =
                new SqliteSchemaV1Manager(connections, () -> -500);
        schemas.initialize();
        assertInstanceOf(
                PersistenceReadResult.Found.class, schemas.verify()
        );
        try (Connection connection = connections.openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO feature_circuit(
                         feature_id, state, failure_count, reason_code,
                         opened_at_ms, updated_at_ms
                     ) VALUES (?, 'OPEN', 1, 'injected_bounded_failure', ?, ?)
                     """)) {
            statement.setString(
                    1, PublicPersistenceFeatureRegistry.CAPTURE.value()
            );
            statement.setLong(2, -400);
            statement.setLong(3, -400);
            statement.executeUpdate();
        }
        PublicPersistenceRuntime runtime = runtime(
                PublicPersistenceWorldReconciliation.alreadyComplete()
        );

        assertTrue(runtime.start().toCompletableFuture().join().complete());
        assertEquals(
                PersistenceReadinessLevel.QUARANTINED,
                runtime.readiness(PublicPersistenceFeatureRegistry.CAPTURE)
        );
        assertEquals(
                PersistenceReadinessLevel.MUTATION_READY,
                runtime.readiness(PublicPersistenceFeatureRegistry.IDENTITY)
        );
        PublicPersistenceDiagnosticsSnapshot diagnostics =
                diagnostics(runtime);
        assertEquals(
                PersistenceFeatureCircuitState.OPEN,
                diagnostics.features().get(
                        PublicPersistenceFeatureRegistry.CAPTURE
                ).circuit().state()
        );
        assertEquals(
                PersistenceFeatureCircuitState.CLOSED,
                diagnostics.features().get(
                        PublicPersistenceFeatureRegistry.IDENTITY
                ).circuit().state()
        );
        runtime.close();
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
        PublicPersistenceOperationalStatus deferredStatus =
                runtime.operationalStatus();
        assertEquals(
                PublicPersistenceOperationalStatus.NodeState.DEFERRED,
                deferredStatus.startupNodes().get(
                        PersistenceStartupNode.WAIT_WORLD_EVIDENCE
                )
        );
        assertTrue(deferredStatus.guidance().contains(
                "wait_for_required_worlds_then_resume_startup"
        ));
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
        PublicPersistencePerformanceSnapshot performance =
                runtime.performance();
        assertTrue(performance.writer().execution().count() > 0);
        assertTrue(performance.writer().maximumDepth() > 0);
        PublicPersistenceDiagnosticsSnapshot diagnostics =
                diagnostics(runtime);
        assertEquals(
                1,
                diagnostics.features().get(
                        PublicPersistenceFeatureRegistry.IDENTITY
                ).operationCounts().get(
                        CompanionProfileMutationDefinition.INSTANCE.kind()
                ).get(OperationPhase.PUBLISHED)
        );
        assertTrue(diagnostics.outboxHead() > 0);
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
        PublicPersistenceOperationalStatus status =
                runtime.operationalStatus();
        assertEquals(
                PublicPersistenceOperationalStatus.NodeState.FAILED,
                status.startupNodes().get(
                        PersistenceStartupNode.WAIT_WORLD_EVIDENCE
                )
        );
        assertEquals(
                PublicPersistenceOperationalStatus.NodeState.BLOCKED,
                status.startupNodes().get(
                        PersistenceStartupNode.RECONCILE_WORLD
                )
        );

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

    private PublicPersistenceDiagnosticsSnapshot diagnostics(
            PublicPersistenceRuntime runtime
    ) {
        PersistenceReadResult<PublicPersistenceDiagnosticsSnapshot> result =
                runtime.diagnostics().toCompletableFuture().join();
        if (result instanceof PersistenceReadResult.Found<
                PublicPersistenceDiagnosticsSnapshot> found) {
            return found.value();
        }
        throw new AssertionError("Expected diagnostics, received " + result);
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
                        .confirmed("coop_release_confirmed").completed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("timed_confirmed").completed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("provisioning_activation_confirmed")
                        .completed(),
                com.alechilles.alecstamework.companion.revival
                        .PaidRevivalBoundaries.unavailable()
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
