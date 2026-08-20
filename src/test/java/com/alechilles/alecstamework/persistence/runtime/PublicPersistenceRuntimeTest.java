package com.alechilles.alecstamework.persistence.runtime;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionKey;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutation;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionMutationAction;
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
import com.alechilles.alecstamework.persistence.adapter.sqlite.SqliteSchemaV2Manager;
import com.alechilles.alecstamework.persistence.control.PersistenceEngineLease;
import com.alechilles.alecstamework.persistence.control.PersistenceEngineLineage;
import com.alechilles.alecstamework.persistence.control.PersistenceFeatureCircuitState;
import com.alechilles.alecstamework.persistence.control.PersistenceReadinessLevel;
import com.alechilles.alecstamework.persistence.control.PersistenceStartupNode;
import com.alechilles.alecstamework.persistence.kernel.PersistenceFiles;
import com.alechilles.alecstamework.persistence.migration.PublicPersistenceTarget;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.PersistenceTransactionResult;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import com.alechilles.alecstamework.persistence.operation.IdempotencyKey;
import com.alechilles.alecstamework.persistence.operation.LiveOperationResult;
import com.alechilles.alecstamework.persistence.operation.OperationId;
import com.alechilles.alecstamework.persistence.operation.OperationPhase;
import com.alechilles.alecstamework.persistence.operation.OperationWorkflowResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
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
        assertEquals(PersistenceEngineLineage.REPLACEMENT, opening.engine());
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
        assertEquals(
                PublicPersistenceFeatureRegistry.create().descriptors().stream()
                        .flatMap(descriptor -> descriptor.projectionConsumers().stream())
                        .collect(Collectors.toUnmodifiableSet()),
                diagnostics.projectionCheckpoints().keySet()
        );
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
                SqliteSchemaV2Manager.VERSION,
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

    /** Regression: normal startup upgrades a populated v1 target before mutation. */
    @Test
    void populatedV1StartupMigratesBeforeRuntimeReadiness() throws Exception {
        Path database = PersistenceFiles.replacementDatabase(tempDir);
        SqliteSchemaV1Manager v1 = new SqliteSchemaV1Manager(
                new SqliteConnectionFactory(database), () -> -500);
        assertInstanceOf(
                PersistenceTransactionResult.Committed.class,
                v1.initialize()
        );
        String profileId = "20000000-0000-0000-0000-000000000099";
        try (Connection connection = new SqliteConnectionFactory(database)
                .openWriterConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO companion_profile(
                         profile_id, display_name, role_id,
                         last_known_world_key, created_at_ms, updated_at_ms,
                         last_active_at_ms, metadata_revision
                     ) VALUES (?, 'Persisted', 'livestock', 'world',
                         -500, -500, -500, 0)
                     """)) {
            statement.setString(1, profileId);
            statement.executeUpdate();
            try (PreparedStatement lifecycle = connection.prepareStatement("""
                    INSERT INTO companion_lifecycle(
                        profile_id, lifecycle_state, location_kind, revision,
                        state_changed_at_ms, last_reconciled_generation
                    ) VALUES (?, 'UNLOADED', 'NONE', 0, -500, 0)
                    """)) {
                lifecycle.setString(1, profileId);
                lifecycle.executeUpdate();
            }
        }

        var activation = new com.alechilles.alecstamework.persistence.activation
                .TameworkPersistenceActivationProbe(database).probe();
        assertEquals(
                com.alechilles.alecstamework.persistence.activation
                        .PersistenceActivationMode.ACTIVE,
                activation.mode()
        );
        assertTrue(activation.evidence().contains(
                "persistence-schema-upgrade-v1"));

        PublicPersistenceRuntime runtime = runtime(
                PublicPersistenceWorldReconciliation.alreadyComplete());
        var startup = runtime.start().toCompletableFuture().join();
        assertTrue(startup.complete(), startup.toString());
        assertEquals(
                PublicPersistenceTarget.Origin.EXISTING,
                runtime.targetOrigin().orElseThrow()
        );
        assertEquals(
                PersistenceReadinessLevel.MUTATION_READY,
                runtime.readiness(PublicPersistenceFeatureRegistry.IDENTITY)
        );
        assertEquals(
                SqliteSchemaV2Manager.VERSION,
                runtime.operationalStatus().schemaVersion().orElseThrow()
        );
        assertEquals(1, queryInt(database,
                "SELECT COUNT(*) FROM companion_profile"));
        assertEquals("Persisted", queryString(database, """
                SELECT display_name FROM companion_profile
                WHERE profile_id = '20000000-0000-0000-0000-000000000099'
                """));
        assertEquals(2, queryInt(database,
                "SELECT COUNT(*) FROM schema_history"));
        assertEquals(1, queryInt(database,
                "SELECT version FROM schema_history ORDER BY rowid LIMIT 1"));
        assertEquals(2, queryInt(database,
                "SELECT version FROM schema_history ORDER BY rowid DESC LIMIT 1"));
        runtime.close();

        List<Path> backups = backupPaths(database);
        assertEquals(1, backups.size());
        assertInstanceOf(
                PersistenceReadResult.Found.class,
                new SqliteSchemaV1Manager(new SqliteConnectionFactory(backups.get(0)))
                        .verify()
        );

        PublicPersistenceRuntime second = runtime(
                PublicPersistenceWorldReconciliation.alreadyComplete());
        assertTrue(second.start().toCompletableFuture().join().complete());
        assertEquals(PublicPersistenceTarget.Origin.EXISTING,
                second.targetOrigin().orElseThrow());
        assertEquals(1, backupPaths(database).size());
        assertEquals(2, queryInt(database,
                "SELECT COUNT(*) FROM schema_history"));
        second.close();
    }

    @Test
    void createsWorldReconciliationOnlyAfterDomainFacadesExist() {
        AtomicReference<PersistenceDomainFacades> received =
                new AtomicReference<>();
        AtomicInteger quiesced = new AtomicInteger();
        PublicPersistenceWorldReconciliationFactory factory = facades -> {
            received.set(facades);
            return new PublicPersistenceWorldReconciliation() {
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
                    quiesced.incrementAndGet();
                }
            };
        };
        PublicPersistenceRuntime runtime = new PublicPersistenceRuntime(
                new PublicPersistenceRuntimeConfiguration(
                        tempDir,
                        "factory-test",
                        () -> -100,
                        (claim, operation) -> LiveOperationResult
                                .confirmed("refund_confirmed")
                                .completed(),
                        ignored -> {
                        },
                        boundaries(),
                        factory,
                        Duration.ofSeconds(5)
                )
        );

        assertTrue(runtime.start().toCompletableFuture().join().complete());
        assertTrue(received.get() != null);
        assertTrue(received.get().operations() != null);
        assertTrue(received.get().queries() != null);
        assertEquals(
                PublicPersistenceShutdownReport.Status.COMPLETE,
                runtime.shutdown(Duration.ofSeconds(5)).status()
        );
        assertEquals(1, quiesced.get());
    }

    @Test
    void durableBoundedCircuitBlocksOnlyItsDescriptor() throws Exception {
        Path database = PersistenceFiles.replacementDatabase(tempDir);
        SqliteConnectionFactory connections =
                new SqliteConnectionFactory(database);
        SqliteSchemaV2Manager schemas =
                new SqliteSchemaV2Manager(connections, () -> -500);
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
        assertEquals(
                profileId(),
                runtime.queries().projectedProfile(profileId())
                        .orElseThrow().profileId()
        );
        ProfileExtensionKey extensionKey =
                new ProfileExtensionKey(profileId(), "example", "value");
        var extension = runtime.operations().mutateExtension(
                OperationId.create(),
                new IdempotencyKey("extension-create"),
                new ProfileExtensionMutation(
                        extensionKey,
                        ProfileExtensionMutationAction.PUT,
                        null,
                        "{\"ready\":true}",
                        -5
                )
        );
        assertEquals(
                OperationWorkflowResult.Status.PUBLISHED,
                extension.completion().toCompletableFuture().join().status()
        );
        assertEquals(
                "{\"ready\":true}",
                runtime.queries().projectedExtension(extensionKey)
                        .orElseThrow().jsonPayload()
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
        assertFalse(timedOut.terminal());
        assertNull(timedOut.kernel());
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
                (request, operation) -> LiveOperationResult
                        .confirmed("capture_confirmed").completed(),
                (request, operation) -> LiveOperationResult
                        .confirmed("capture_release_confirmed").completed(),
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

    private int queryInt(Path database, String sql) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openReadConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    private String queryString(Path database, String sql) throws Exception {
        try (Connection connection = new SqliteConnectionFactory(database)
                .openReadConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private List<Path> backupPaths(Path database) throws Exception {
        String prefix = database.getFileName() + ".v1-backup.";
        try (var paths = Files.list(database.getParent())) {
            return paths.filter(path -> path.getFileName().toString()
                    .startsWith(prefix))
                    .sorted()
                    .toList();
        }
    }
}
